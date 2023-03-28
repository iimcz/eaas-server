package com.openslx.eaas.generalization;

import com.openslx.eaas.generalization.api.ImageGeneralizationApi;
import org.apache.tamaya.ConfigurationProvider;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.core.HttpHeaders;
import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.util.function.Supplier;


public class ImageGeneralizationClient implements Closeable
{
	private final ImageGeneralizationApi proxy;
	private final Supplier<String> token;

	protected ImageGeneralizationClient(ImageGeneralizationApi proxy, Supplier<String> token)
	{
		this.proxy = proxy;
		this.token = token;
	}

	public ImageGeneralizationApi api()
	{
		return proxy;
	}

	@Override
	public void close() throws IOException
	{
		((Closeable) proxy).close();
	}

	public static ImageGeneralizationClient create(Supplier<String> token) throws Exception
	{
		final var endpoint = ConfigurationProvider.getConfiguration()
				.get("generalization.rest_url");

		return ImageGeneralizationClient.create(endpoint, token);
	}

	public static ImageGeneralizationClient create(String endpoint, Supplier<String> token) throws Exception
	{
		final var proxy = RestClientBuilder.newBuilder()
				.baseUrl(new URL(endpoint))
				.register(new AuthFilter(token))
				.build(ImageGeneralizationApi.class);

		return new ImageGeneralizationClient(proxy, token);
	}


	// ===== Internal Helpers ===============

	private static class AuthFilter implements ClientRequestFilter
	{
		private final Supplier<String> token;

		public AuthFilter(Supplier<String> token)
		{
			this.token = token;
		}

		@Override
		public void filter(ClientRequestContext context) throws IOException
		{
			final var value = token.get();
			if (value == null)
				return;

			context.getHeaders()
					.add(HttpHeaders.AUTHORIZATION, "Bearer " + value);
		}
	}
}
