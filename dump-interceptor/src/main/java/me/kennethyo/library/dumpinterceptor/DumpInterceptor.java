/*
 * Copyright (C) 2017 KennethYo, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.kennethyo.library.dumpinterceptor;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import okhttp3.Connection;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.cache.DiskLruCache;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.io.FileSystem;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;

/**
 * Created by kenneth on 2017/11/3.
 */
public class DumpInterceptor implements Interceptor {
    public static final String TAG = "dump-interceptor";
    public static final boolean DEBUG = true;

    private static final String DIR_NAME = "net-log";
    private static long MAX_SIZE = 10 * 1024 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Level mLevel = Level.NONE;
    private Secret mSecret = new DefaultSecret();
    private final DiskLruCache mDiskLruCache;

    public enum Level {
        NONE,
        BASIC,
        HEADERS,
        BODY
    }

    public DumpInterceptor(Context context, long maxSize) {
        File diskCacheDir = Utils.getDiskCacheDir(context, DIR_NAME);
        int appVersionCode = Utils.getAppVersionCode(context);
        int valueCount = 1;
        if (maxSize < MAX_SIZE) {
            maxSize = MAX_SIZE;
        }

        mDiskLruCache = DiskLruCache.create(FileSystem.SYSTEM, diskCacheDir, appVersionCode, valueCount, maxSize);
    }


    @Override
    public Response intercept(Chain chain) throws IOException {
        Level level = mLevel;

        Request request = chain.request();
        if (level == Level.NONE) {
            return chain.proceed(request);
        }
        StringBuilder sb = new StringBuilder();
        String url = request.url().toString();

        boolean dumpBody = level == Level.BODY;
        boolean dumpHeaders = dumpBody || level == Level.HEADERS;

        // 1,request 内容
        RequestBody requestBody = request.body();
        boolean hasRequestBody = requestBody != null;

        Connection connection = chain.connection();
        Protocol protocol = connection != null ? connection.protocol() : Protocol.HTTP_1_1;
        String requestStartMessage = "--> " + request.method() + ' ' + request.url() + ' ' + protocol;
        if (!dumpHeaders && hasRequestBody) {
            requestStartMessage += " (" + requestBody.contentLength() + "-byte body)";
        }
        sb.append(requestStartMessage).append("\n");

        if (dumpHeaders) {
            if (hasRequestBody) {
                if (requestBody.contentType() != null) {
                    sb.append("Content-Type: ").append(requestBody.contentType()).append("\n");
                }
                if (requestBody.contentLength() != -1) {
                    sb.append("Content-Length: ").append(requestBody.contentLength()).append("\n");
                }
            }
            Headers headers = request.headers();
            for (int i = 0, count = headers.size(); i < count; i++) {
                String name = headers.name(i);
                if (!"Content-Type".equalsIgnoreCase(name) && !"Content-Length".equalsIgnoreCase(name)) {
                    sb.append(name).append(": ").append(headers.value(i)).append("\n");
                }
            }

            if (!dumpBody || !hasRequestBody) {
                sb.append("--> END ").append(request.method()).append("\n");
            } else if (Utils.bodyEncoded(headers)) {
                sb.append("--> END ").append(request.method()).append(" (encoded body omitted)").append("\n");
            } else {
                Buffer buffer = new Buffer();
                requestBody.writeTo(buffer);

                Charset charset = UTF8;
                MediaType contentType = requestBody.contentType();
                if (contentType != null) {
                    charset = contentType.charset(charset);
                }

                sb.append("\n");
                if (Utils.isPlaintext(buffer)) {
                    if (charset != null) {
                        sb.append(buffer.readString(charset)).append("\n");
                    } else {
                        sb.append("charset is null, this is warning!").append("\n");
                    }
                    sb.append("--> END ").append(request.method())
                            .append(" (").append(requestBody.contentLength()).append("-byte body)");
                } else {
                    sb.append("--> END ").append(request.method())
                            .append("(").append(requestBody.contentLength()).append("-byte body omitted)").append("\n");
                }

            }
        }
        // 2，response 内容
        long startNs = System.nanoTime();
        Response response;
        try {
            response = chain.proceed(request);
        } catch (Exception e) {
            sb.append("<-- HTTP FAILED: ").append(e).append("\n");
            throw e;
        }
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            // 这里如果为 null 日志其实在上面的 catch 也会有异常信息
            sb.append("<-- HTTP FAILED: ").append("response is null").append("\n");
            dumping(url, sb.toString());
            return response;
        }

        long contentLength = responseBody.contentLength();
        String bodySize = contentLength != -1 ? contentLength + "-byte" : "unknown-length";


        sb.append("<-- ").append(response.code()).append(' ').append(response.message()).append(' '
        ).append(response.request().url()).append(" (").append(tookMs).append("ms");
        if (!dumpHeaders) {
            sb.append(", ").append(bodySize).append(" body");
        }
        sb.append(')').append("\n");

        if (dumpHeaders) {
            Headers headers = response.headers();
            for (int i = 0, count = headers.size(); i < count; i++) {
                sb.append(headers.name(i)).append(": ").append(headers.value(i)).append("\n");
            }

            if (!dumpBody || !HttpHeaders.hasBody(response)) {
                sb.append("<-- END HTTP").append("\n");
            } else if (Utils.bodyEncoded(response.headers())) {
                sb.append("<-- END HTTP (encoded body omitted)").append("\n");
            } else {
                BufferedSource source = responseBody.source();
                source.request(Long.MAX_VALUE); // Buffer the entire body.
                Buffer buffer = source.buffer();

                Charset charset = UTF8;
                MediaType contentType = responseBody.contentType();
                if (contentType != null) {
                    charset = contentType.charset(UTF8);
                }

                if (!Utils.isPlaintext(buffer)) {
                    sb.append("\n");
                    sb.append("<-- END HTTP (binary ").append(buffer.size()).append("-byte body omitted)");
                    dumping(url, sb.toString());
                    return response;
                }

                if (contentLength != 0) {
                    sb.append("\n");
                    if (charset != null) {
                        sb.append(buffer.clone().readString(charset));
                    } else {
                        sb.append("charset is null, this is warning!");
                    }
                }
                sb.append("\n");
                sb.append("<-- END HTTP (").append(buffer.size()).append("-byte body)");
            }
        }

        dumping(url, sb.toString());
        return response;
    }

    private void dumping(String key, String log) throws IOException {
        Utils.log(log);
        DiskLruCache.Editor editor = mDiskLruCache.edit(mSecret.encodeFileName(key + System.currentTimeMillis()));
        if (editor != null) {
//            Buffer buffer = new Buffer();
//            buffer.write(mSecret.encodeFileContent(log));

            Sink sink = editor.newSink(0);
            BufferedSink buffer = Okio.buffer(sink);
            buffer.writeUtf8(mSecret.encodeFileContent(log));
            buffer.flush();
            buffer.close();
            editor.commit();
            mDiskLruCache.flush();
        }
    }

    public DumpInterceptor setLevel(Level level) {
        if (level == null) throw new NullPointerException("level == null");
        mLevel = level;
        return this;
    }

    public DumpInterceptor setSecret(Secret secret) {
        if (secret == null) throw new NullPointerException("secret == null");
        mSecret = secret;
        return this;
    }

    public interface Secret {
        String encodeFileName(String fileName);

        String encodeFileContent(String content);
    }

    class DefaultSecret implements Secret {
        @Override
        public String encodeFileName(String fileName) {
            return Utils.hashKeyForDisk(fileName);
        }

        @Override
        public String encodeFileContent(String content) {
            return content;
        }
    }

}
