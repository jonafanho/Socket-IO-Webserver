package org.mtr.webserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class Test {

	public static void main(String[] args) {
		final Webserver webserver = new Webserver(Test.class, "website", 8888, jsonObject -> 0);
		webserver.addGetListener("/test/*", Test::sendJson);
		webserver.addGetListener("/delay/*", (queryStringDecoder, sendRequest) -> Executors.newSingleThreadScheduledExecutor().schedule(() -> sendJson(queryStringDecoder, sendRequest), 5, TimeUnit.SECONDS));
		webserver.start();
		System.out.println("Server started");
	}

	private static void sendJson(QueryStringDecoder queryStringDecoder, BiConsumer<JsonObject, HttpResponseStatus> sendRequest) {
		final JsonObject parametersObject = new JsonObject();
		queryStringDecoder.parameters().forEach((key, value) -> {
			final JsonArray valuesArray = new JsonArray();
			value.forEach(valuesArray::add);
			parametersObject.add(key, valuesArray);
		});

		final JsonObject responseObject = new JsonObject();
		responseObject.addProperty("time", System.currentTimeMillis());
		responseObject.addProperty("uri", queryStringDecoder.uri());
		responseObject.addProperty("path", queryStringDecoder.path());
		responseObject.add("parameters", parametersObject);

		sendRequest.accept(responseObject, HttpResponseStatus.OK);
	}
}
