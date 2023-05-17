package org.mtr.webserver;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOChannelInitializer;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.ToLongFunction;

public class Webserver {

	private final SocketIOServer server;
	private final ToLongFunction<JsonObject> getId;
	private final Object2ObjectAVLTreeMap<String, HttpRequestListener> httpListeners = new Object2ObjectAVLTreeMap<>();
	private final Long2ObjectAVLTreeMap<SocketIOClient> clients = new Long2ObjectAVLTreeMap<>();

	/**
	 * A webserver that serves static files, HTTP requests, and Socket.IO websockets.
	 *
	 * @param mainClass     the class file for finding a resource from.
	 * @param resourcesRoot the root directory to serve files from.
	 * @param port          the port number for this webserver.
	 * @param charset       the {@link Charset} for encoding the response
	 * @param getId         a function to get a unique ID (represented as a long) from socket {@link JsonObject}.
	 */
	public Webserver(Class<?> mainClass, String resourcesRoot, int port, Charset charset, ToLongFunction<JsonObject> getId) {
		final Configuration configuration = new Configuration();
		configuration.setPort(port);
		configuration.getSocketConfig().setReuseAddress(true);
		configuration.setAllowCustomRequests(true);

		server = new SocketIOServer(configuration);
		server.setPipelineFactory(new CustomSocketIOChannelInitializer(mainClass, resourcesRoot, charset, httpListeners));
		server.addDisconnectListener(client -> {
			final LongArrayList idsToRemove = new LongArrayList();
			clients.forEach((id, checkClient) -> {
				if (client.equals(checkClient)) {
					idsToRemove.add(id.longValue());
				}
			});
			idsToRemove.forEach(clients::remove);
		});

		this.getId = getId;
	}

	public void start() {
		server.start();
	}

	public void stop() {
		server.stop();
	}

	public void sendSocketEvent(long id, String channel, JsonObject jsonObject) {
		sendSocketEvent(clients.get(id), channel, jsonObject);
	}

	public void sendSocketEvent(SocketIOClient client, String channel, JsonObject jsonObject) {
		if (client != null) {
			client.sendEvent(channel, (jsonObject == null ? new JsonObject() : jsonObject).toString());
		}
	}

	public void addSocketListener(String channel, SocketListener socketListener) {
		server.addEventListener(channel, String.class, (client, message, ackRequest) -> {
			try {
				final JsonObject jsonObject = JsonParser.parseString(message).getAsJsonObject();
				final long id = getId.applyAsLong(jsonObject);
				clients.put(id, client);
				socketListener.accept(client, id, jsonObject);
			} catch (Exception ignored) {
			}
		});
	}

	public void addHttpListener(String path, HttpRequestListener httpRequestListener) {
		httpListeners.put(path, httpRequestListener);
	}

	private static class CustomSocketIOChannelInitializer extends SocketIOChannelInitializer {

		private final Class<?> mainClass;
		private final String resourcesRoot;
		private final Charset charset;
		private final Object2ObjectAVLTreeMap<String, HttpRequestListener> httpListeners;

		private CustomSocketIOChannelInitializer(Class<?> mainClass, String resourcesRoot, Charset charset, Object2ObjectAVLTreeMap<String, HttpRequestListener> httpListeners) {
			super();
			this.mainClass = mainClass;
			this.resourcesRoot = resourcesRoot;
			this.charset = charset;
			this.httpListeners = httpListeners;
		}

		@Override
		protected void addSocketioHandlers(ChannelPipeline pipeline) {
			super.addSocketioHandlers(pipeline);
			pipeline.addBefore(WRONG_URL_HANDLER, "custom", new CustomChannelInboundHandlerAdapter(mainClass, resourcesRoot, charset, httpListeners));
		}
	}

	private static class CustomChannelInboundHandlerAdapter extends SimpleChannelInboundHandler<FullHttpRequest> {

		private final Class<?> mainClass;
		private final String resourcesRoot;
		private final Charset charset;
		private final Object2ObjectAVLTreeMap<String, HttpRequestListener> httpListeners;

		private CustomChannelInboundHandlerAdapter(Class<?> mainClass, String resourcesRoot, Charset charset, Object2ObjectAVLTreeMap<String, HttpRequestListener> httpListeners) {
			super();
			this.mainClass = mainClass;
			this.resourcesRoot = resourcesRoot;
			this.charset = charset;
			this.httpListeners = httpListeners;
		}

		@Override
		protected void channelRead0(ChannelHandlerContext channelHandlerContext, FullHttpRequest fullHttpRequest) {
			final String fullUri = fullHttpRequest.uri();
			final String uri = removeLastSlash(fullUri.split("\\?")[0]);

			for (final Map.Entry<String, HttpRequestListener> entry : httpListeners.entrySet()) {
				final String path = entry.getKey();
				if (path.endsWith("*") && uri.startsWith(path.replace("*", "")) || uri.equals(removeLastSlash(path))) {
					JsonObject bodyObject = new JsonObject();
					try {
						bodyObject = JsonParser.parseString(fullHttpRequest.content().toString(StandardCharsets.UTF_8)).getAsJsonObject();
					} catch (Exception ignored) {
					}

					entry.getValue().accept(new QueryStringDecoder(removeLastSlash(fullUri.replaceFirst("/\\?", "?"))), bodyObject, (jsonObject, httpResponseStatus) -> {
						final HttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, httpResponseStatus, Unpooled.wrappedBuffer(jsonObject.toString().getBytes(charset)));
						httpResponse.headers().add(HttpHeaderNames.CONTENT_TYPE, getMimeType("json")).add(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
						channelHandlerContext.channel().writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);
					});

					return;
				}
			}

			sendFile(uri, (byteBuf, mimeType) -> {
				final HttpResponse httpResponse;
				if (byteBuf == null) {
					httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
				} else {
					httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, byteBuf);
					httpResponse.headers().add(HttpHeaderNames.CONTENT_TYPE, mimeType);
				}
				channelHandlerContext.channel().writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);
			}, true);
		}

		private void sendFile(String fileName, BiConsumer<ByteBuf, String> consumer, boolean shouldRetry) {
			final URL url = mainClass.getResource(resourcesRoot + fileName);

			if (url != null) {
				try {
					final File file = new File(url.toURI());
					try (final FileInputStream fileInputStream = new FileInputStream(file)) {
						try (final FileChannel fileChannel = fileInputStream.getChannel()) {
							consumer.accept(Unpooled.wrappedBuffer(fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())), getMimeType(fileName));
							return;
						}
					}
				} catch (Exception ignored) {
				}
			}

			if (shouldRetry) {
				sendFile("/index.html", consumer, false);
			} else {
				consumer.accept(null, "");
			}
		}

		private static String getMimeType(String fileName) {
			final String[] fileNameSplit = fileName.split("\\.");
			final String fileExtension = fileNameSplit.length == 0 ? "" : fileNameSplit[fileNameSplit.length - 1];
			switch (fileExtension) {
				case "js":
					return "text/javascript";
				case "json":
					return "application/json";
				default:
					return "text/" + fileExtension;
			}
		}

		private static String removeLastSlash(String text) {
			if (text.charAt(text.length() - 1) == '/') {
				return text.substring(0, text.length() - 1);
			} else {
				return text;
			}
		}
	}

	@FunctionalInterface
	public interface SocketListener {
		void accept(SocketIOClient client, long id, JsonObject jsonObject);
	}

	@FunctionalInterface
	public interface HttpRequestListener {
		void accept(QueryStringDecoder queryStringDecoder, JsonObject bodyObject, BiConsumer<JsonObject, HttpResponseStatus> consumer);
	}
}
