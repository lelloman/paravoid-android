package com.lelloman.paravoidcompat.network;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;

public interface GsonApi {
    final class Item {
        @SerializedName("display_name") public String name;
        public int count;
    }
    final class Envelope<T> {
        public T data;
        public String run;
    }
    @GET("items") Call<Envelope<List<Item>>> items();
    @POST("echo") Call<Envelope<List<Item>>> echo(@Body Envelope<List<Item>> value);
    @GET("error") Call<Envelope<List<Item>>> error();
    @GET("slow") Call<Envelope<List<Item>>> slow();
    default String identity() { return "default-method"; }
}
