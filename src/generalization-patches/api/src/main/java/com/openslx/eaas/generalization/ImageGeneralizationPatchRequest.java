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

package com.openslx.eaas.generalization;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;


public class ImageGeneralizationPatchRequest
{
	private String archive;
	private String imageId;
	private String imageType;

	@JsonSetter("imageId")
	public void setImageId(String imageId)
	{
		this.imageId = imageId;
	}

	@JsonGetter("imageId")

	public String getImageId()
	{
		return imageId;
	}

	@JsonSetter("archive")
	public void setArchive(String archive)
	{
		this.archive = archive;
	}

	@JsonGetter("archive")
	public String getArchive()
	{
		return archive;
	}

	@JsonSetter("imageType")
	public void setImageType(String imageType)
	{
		this.imageType = imageType;
	}

	@JsonGetter("imageType")
	public String getImageType()
	{
		return imageType;
	}
}
