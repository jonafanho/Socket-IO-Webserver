package org.mtr.webserver;

import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.io.netty.handler.codec.http.HttpResponseStatus;
import org.mtr.libraries.io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class Test {

	public static void main(String[] args) {
		final Webserver webserver = new Webserver(Test.class, "website", 8888, StandardCharsets.UTF_8, jsonObject -> 0);
		webserver.addHttpListener("/test/*", Test::sendJson);
		webserver.addHttpListener("/delay/*", (queryStringDecoder, bodyObject, sendRequest) -> Executors.newSingleThreadScheduledExecutor().schedule(() -> sendJson(queryStringDecoder, bodyObject, sendRequest), 5, TimeUnit.SECONDS));
		webserver.start();
		System.out.println("Server started");
	}

	private static void sendJson(QueryStringDecoder queryStringDecoder, JsonObject bodyObject, BiConsumer<JsonObject, HttpResponseStatus> sendResponse) {
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
		responseObject.add("body", bodyObject);

		sendResponse.accept(responseObject, HttpResponseStatus.OK);
	}
}
