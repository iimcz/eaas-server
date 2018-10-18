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

package de.bwl.bwfla.emil.datatypes;

import de.bwl.bwfla.common.utils.jaxb.JaxbType;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.concurrent.TimeUnit;


@XmlRootElement
@XmlAccessorType(XmlAccessType.NONE)
public class SessionLifetimeRequest extends JaxbType
{
	@XmlElement(required = true)
	private long lifetime;

	private TimeUnit unit;


	public long getLifetime()
	{
		return lifetime;
	}

	public void setLifetime(long lifetime)
	{
		this.lifetime = lifetime;
	}

	public TimeUnit getLifetimeUnit()
	{
		return unit;
	}

	@XmlElement(name = "lifetime_unit",required = true)
	public void setLifetimeUnit(String unitstr)
	{
		switch (unitstr) {
			case "milliseconds":
				this.unit = TimeUnit.MILLISECONDS;
				break;
			case "seconds":
				this.unit = TimeUnit.SECONDS;
				break;
			case "minutes":
				this.unit = TimeUnit.MINUTES;
				break;
			case "hours":
				this.unit = TimeUnit.HOURS;
				break;
			case "days":
				this.unit = TimeUnit.DAYS;
				break;
			default:
				throw new IllegalArgumentException("Unsupported time unit: " + unitstr);
		}
	}
}
