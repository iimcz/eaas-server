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

package de.bwl.bwfla.emil.session;


public class SessionComponent
{
	private final String id;
	private final long ctime;
	private volatile boolean released;
	private volatile boolean removed;
	private boolean ephemeral;
	private NetworkConnectionInfo netinfo;
	private String customName = null;


	public SessionComponent(String id)
	{
		this.id = id;
		this.ctime = System.nanoTime();
		this.released = false;
		this.removed = false;
		this.ephemeral = false;
	}

	public String id()
	{
		return id;
	}

	public NetworkConnectionInfo getNetworkConnectionInfo()
	{
		if (netinfo == null)
			netinfo = new NetworkConnectionInfo();

		return netinfo;
	}

	public void setNetworkConnectionInfo(NetworkConnectionInfo info)
	{
		this.netinfo = info;
	}

	public void resetNetworkConnectionInfo()
	{
		this.netinfo = null;
	}

	public String getCustomName()
	{
		return customName;
	}

	public void setCustomName(String customName)
	{
		this.customName = customName;
	}

	public long getCreationTime()
	{
		return ctime;
	}

	public void markAsReleased()
	{
		this.released = true;
	}

	public boolean isReleased()
	{
		return released;
	}

	public void markAsRemoved()
	{
		this.removed = true;
	}

	public boolean isRemoved()
	{
		return removed;
	}

	public void markAsEphemeral()
	{
		this.ephemeral = true;
	}

	public boolean isEphemeral()
	{
		return ephemeral;
	}

	public static class NetworkConnectionInfo
	{
		private String ethurl;

		public String getEthernetUrl()
		{
			return ethurl;
		}

		public void setEthernetUrl(String ethurl)
		{
			this.ethurl = ethurl;
		}

		public boolean isConnected()
		{
			return ethurl != null;
		}
	}
}