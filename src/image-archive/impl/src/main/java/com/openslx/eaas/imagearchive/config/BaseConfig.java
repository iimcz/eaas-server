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

package com.openslx.eaas.imagearchive.config;

import de.bwl.bwfla.common.utils.ConfigHelpers;
import org.apache.tamaya.ConfigException;
import org.apache.tamaya.Configuration;
import org.apache.tamaya.ConfigurationProvider;

import java.util.logging.Logger;


public abstract class BaseConfig<D extends BaseConfig<D>>
{
	protected D load(Logger log)
	{
		return this.load(ConfigurationProvider.getConfiguration(), log);
	}

	protected D load(Configuration config, Logger log) throws ConfigException
	{
		final var self = (D) this;

		// Configure annotated members of this instance
		return ConfigHelpers.configure(self, config);
	}
}
