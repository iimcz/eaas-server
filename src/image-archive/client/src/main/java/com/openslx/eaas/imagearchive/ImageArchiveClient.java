/*
 * This file is part of the Emulation-as-a-Service framework.
 *
 * The Emulation-as-a-Service framework is free software: you can
 * redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * The Emulation-as-a-Service framework is distributed in the hope that
 * it will be useful, but WITHOUT ANY WARRANTY; without even the
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Emulation-as-a-Software framework.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.openslx.eaas.imagearchive;

import com.openslx.eaas.imagearchive.api.ImageArchiveApi;
import com.openslx.eaas.imagearchive.client.endpoint.ImageArchive;
import de.bwl.bwfla.common.services.security.MachineToken;
import de.bwl.bwfla.common.services.security.MachineTokenProvider;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.core.HttpHeaders;
import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.util.logging.Logger;


public class ImageArchiveClient implements Closeable
{
	private final Logger logger;
	private final ImageArchiveApi proxy;
	private final ImageArchive api;


	public ImageArchive api()
	{
		return api;
	}

	public Logger logger()
	{
		return logger;
	}

	@Override
	public void close() throws IOException
	{
		// NOTE: according to docs, casting should be safe!
		((Closeable) proxy).close();
	}

	public static ImageArchiveClient create() throws Exception
	{
		return ImageArchiveClient.create("http://localhost:8080/image-archive");
	}

	public static ImageArchiveClient create(String endpoint) throws Exception
	{
		final var proxy = RestClientBuilder.newBuilder()
				.baseUrl(new URL(endpoint))
				.register(new AuthFilter())
				.build(ImageArchiveApi.class);

		return new ImageArchiveClient(proxy);
	}


	// ===== Internal Helpers ===============

	private ImageArchiveClient(ImageArchiveApi proxy)
	{
		this.logger = Logger.getLogger("IMAGE-ARCHIVE-CLIENT");
		this.proxy = proxy;
		this.api = new ImageArchive(proxy, logger);
	}

	private static class AuthFilter implements ClientRequestFilter
	{
		private final MachineToken token;

		private AuthFilter()
		{
			this.token = MachineTokenProvider.getInternalToken();
		}

		@Override
		public void filter(ClientRequestContext context) throws IOException
		{
			context.getHeaders()
					.add(HttpHeaders.AUTHORIZATION, token.get());
		}
	}
}
