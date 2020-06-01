package hex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.lang.StringUtils;
import org.codelibs.curl.Curl;
import org.codelibs.curl.CurlResponse;
import water.MRTask;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.NewChunk;
import water.fvec.Vec;
import water.parser.BufferedString;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RegexTokenizer splits rows of a given Frame into delimited sequences of tokens using a regular expression.
 * The output structure is suitable for use in the word2vec algorithm.
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * final RegexTokenizer tokenizer = new RegexTokenizer.Builder()
 *   .setRegex("[,;]")
 *   .setMinLength(2)
 *   .setToLowercase(true)
 *   .create();
 * final Frame tokens = tokenizer.transform(inputFrame);
 * }
 * </pre>
 */
public class ElasticsearchTokenizer extends MRTask<ElasticsearchTokenizer> {
    private final String url;
    private final int minLength;
    private final String analyzer;

    public ElasticsearchTokenizer(String url) {
        this(url, 0);
    }

    private ElasticsearchTokenizer(String url, int minLength) {
        String[] values = StringUtils.split(url, "?", 2);
        if (values.length == 2) {
            this.url = values[0];
            final Map<String, String> params = new HashMap<>();
            for (String value : values[1].split("&")) {
                String[] pair = StringUtils.split(value, "=", 2);
                if (pair.length == 2) {
                    params.put(pair[0], pair[1]);
                } else {
                    params.put(value, "");
                }
            }
            this.analyzer = params.getOrDefault("analyzer", null);
        } else {
            this.url = url;
            this.analyzer = null;
        }
        this.minLength = minLength;
    }

    @Override
    public void map(Chunk[] cs, NewChunk nc) {
        final Gson gson = new GsonBuilder().create();
        final BufferedString tmpStr = new BufferedString();
        for (int row = 0; row < cs[0]._len; row++) {
            for (Chunk chk : cs) {
                if (chk.isNA(row))
                    continue; // input NAs are skipped
                final String str = chk.atStr(tmpStr, row).toString();
                final String[] ss = tokenize(gson, str);
                for (String s : ss) {
                    if (s.length() >= minLength) {
                        nc.addStr(s);
                    }
                }
            }
            nc.addNA(); // sequences of tokens are delimited by NAs
        }
    }

    private String[] tokenize(final Gson gson, final String str) {
        final Map<String, String> params = new HashMap<>();
        params.put("text", str);
        if (analyzer != null) {
            params.put("analyzer", analyzer);
        }
        final String json = gson.toJson(params);
        try (CurlResponse response = Curl.post(url).header("Content-Type", "application/json").body(json).execute()) {
            final Tokens tokens = response.getContent(res -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(res.getContentAsStream(), StandardCharsets.UTF_8))) {
                    return gson.fromJson(reader, Tokens.class);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            return tokens.tokens.stream().map(t -> t.token).toArray(String[]::new);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Tokenizes a given Frame
     * @param input Input Frame is expected to only contain String columns. Each row of the Frame represents a logical
     *              sentence. The sentence can span one or more cells of the row.
     * @return Frame made of a single String column where original sentences are split into tokens and delimited by NAs.
     */
    public Frame transform(Frame input) {
        return doAll(Vec.T_STR, input).outputFrame();
    }

    public static class Builder {
        private String url;
        private int minLength;

        public Builder setUrl(String regex) {
            this.url = regex;
            return this;
        }

        public Builder setMinLength(int minLength) {
            this.minLength = minLength;
            return this;
        }

        public ElasticsearchTokenizer create() {
            return new ElasticsearchTokenizer(url, minLength);
        }
    }

    static class Tokens {
        public List<Token> tokens = new ArrayList<>();
    }

    static class Token {
        public String token;
        public int start_offset;
        public int end_offset;
        public String type;
        public int position;
    }
}
