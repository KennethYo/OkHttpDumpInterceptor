package me.kennethyo.dumpinterceptor;

import android.app.Application;

import me.kennethyo.library.dumpinterceptor.DumpInterceptor;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Created by kenneth on 2017/11/3.
 */

public class MyApplication extends Application {

    private Retrofit mRetrofit;
    private static MyApplication myApplication;

    public static MyApplication getMyApplication() {
        return myApplication;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        myApplication = this;
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        DumpInterceptor dump = new DumpInterceptor(this,2*1000*1000);
        dump.setLevel(DumpInterceptor.Level.BODY);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .addInterceptor(dump)
                .build();

        mRetrofit = new Retrofit.Builder()
                .client(client)
                .baseUrl("https://api.github.com/")
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(GsonConverterFactory.create())
                .build();
    }

    public Retrofit getRetrofit() {
        return mRetrofit;
    }
}
