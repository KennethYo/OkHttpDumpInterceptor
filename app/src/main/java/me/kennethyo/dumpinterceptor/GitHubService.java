package me.kennethyo.dumpinterceptor;

import java.util.List;

import io.reactivex.Observable;
import retrofit2.http.GET;
import retrofit2.http.Path;

/**
 * Created by kenneth on 2017/11/3.
 */

public interface GitHubService {
    @GET("users/{user}/repos")
    Observable<List<Repo>> listRepos(@Path("user") String user);

    @GET("http://beijing.bitauto.com/")
    Observable<String> yiche();
}
