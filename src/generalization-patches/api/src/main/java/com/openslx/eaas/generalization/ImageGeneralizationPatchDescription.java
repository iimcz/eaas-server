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

package com.openslx.eaas.generalization;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import de.bwl.bwfla.common.utils.jaxb.JaxbType;


public class ImageGeneralizationPatchDescription extends JaxbType
{
	private String name;
	private String description;


	public ImageGeneralizationPatchDescription()
	{
		// Empty!
	}

	public ImageGeneralizationPatchDescription(String name, String description)
	{
		this.name = name;
		this.description = description;
	}

	@JsonSetter("name")
	public void setName(String name)
	{
		this.name = name;
	}

	@JsonGetter("name")
	public String getName()
	{
		return name;
	}

	@JsonSetter("description")
	public void setDescription(String description)
	{
		this.description = description;
	}

	@JsonGetter("description")
	public String getDescription()
	{
		return description;
	}
}
