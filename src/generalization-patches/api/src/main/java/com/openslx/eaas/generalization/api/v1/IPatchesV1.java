/*
 * This file is part of the Emulation-as-a-Service framework.
 *
 * The Emulation-as-a-Service framework is free software: you can
 * redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * The Emulation-as-a-Service framework is distributed in the hope that
 * it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Emulation-as-a-Software framework.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.openslx.eaas.generalization.api.v1;

import com.openslx.eaas.common.databind.Streamable;
import com.openslx.eaas.generalization.ImageGeneralizationPatchDescription;
import com.openslx.eaas.generalization.ImageGeneralizationPatchRequest;
import com.openslx.eaas.generalization.ImageGeneralizationPatchResponse;
import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.common.services.security.Role;
import de.bwl.bwfla.common.services.security.Secured;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;


public interface IPatchesV1
{
	@GET
	@Secured(roles = {Role.RESTRICTED})
	@Produces(MediaType.APPLICATION_JSON)
	Streamable<ImageGeneralizationPatchDescription> list();

	@POST
	@Path("/{patchId}")
	@Secured(roles = {Role.RESTRICTED})
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	ImageGeneralizationPatchResponse apply(@PathParam("patchId") String patchId, ImageGeneralizationPatchRequest request)
			throws BWFLAException;
}
