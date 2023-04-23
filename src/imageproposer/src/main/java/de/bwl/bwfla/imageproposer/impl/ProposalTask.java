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

package de.bwl.bwfla.imageproposer.impl;

import java.util.*;
import java.util.stream.Collectors;

import com.openslx.eaas.common.databind.DataUtils;
import de.bwl.bwfla.common.datatypes.identification.DiskType;
import de.bwl.bwfla.common.datatypes.identification.OperatingSystemInformation;
import de.bwl.bwfla.common.taskmanager.BlockingTask;
import de.bwl.bwfla.imageproposer.client.Proposal;
import de.bwl.bwfla.imageproposer.client.ProposalRequest;

import javax.xml.bind.JAXBException;


public class ProposalTask extends BlockingTask<Object>
{
	private final ProposalRequest request;
	private final ImageIndexHandle indexHandle;
	private final ImageSorter sorter;

	public ProposalTask(ProposalRequest request, ImageIndexHandle index, ImageSorter sorter)
	{
		this.request = request;
		this.indexHandle = index;
		this.sorter = sorter;
	}

	private Set<String> getExtensions() throws JAXBException
	{
		Set<String> extensions = new HashSet<>();
		for (String key : request.getMediaFormats().keySet()) {
			if (request.getMediaFormats().get(key) != null) {
				DiskType diskType = request.getMediaFormats().get(key);
				log.info(diskType.JSONvalue(true));

				String fileName = diskType.getLocalAlias();
				if (fileName == null)
					continue;

				String ext = getFileExtension(fileName);
				if (ext == null) continue;
				extensions.add(ext.trim().toLowerCase());
				log.info("found extension: " + ext);
			}
		}
		if (request.getFiles() != null) {
			for (var key : request.getFiles().keySet()) {
				List<String> fileList;
				if ((fileList = request.getFiles().get(key)) != null) {
					for (String file : fileList) {
						String ext = getFileExtension(file);
						if (ext == null) continue;
						extensions.add(ext.trim().toLowerCase());
						log.info("found extension: " + ext);
					}
				}
			}
		}

		return extensions;
	}

	private static String getFileExtension(String fileName)
	{
		int index = fileName.lastIndexOf('.');
		if (index < 0)
			return null;
		String ext = fileName.substring(index + 1);
		return ext;
	}

	private void proposeByExtension(ImageIndex index,
									Collection<String> envIdResults,
									Map<String, String> osSuggestion) throws JAXBException
	{
		log.info("Could not find suitable images through PUIDs, now trying file extensions...");

		Set<String> extensions = getExtensions();
		if (getExtensions().isEmpty()) {
			log.info("Did not get any extensions, no results will be added.");
			return;
		}

		propose(index, extensions, envIdResults, osSuggestion, true);

	}

	private void proposeByPUID(ImageIndex index,
							   Collection<String> envIdResults,
							   Map<String, String> osSuggestion)
	{
		Set<String> puids = request.getFileFormats().values().stream()
				.flatMap(List::stream)
				.map(ProposalRequest.Entry::getType)
				.collect(Collectors.toSet());

		propose(index, puids, envIdResults, osSuggestion, false);
	}

	private void propose(ImageIndex index, Set<String> values, Collection<String> envIdResults, Map<String, String> osSuggestion, boolean byExtension)
	{
		int maxCount = 0;
		HashMap<String, Integer> resultMap = new HashMap<>(); // count hits per environment

		log.info("Running propose algorithm...");
		for (var entry : values) {
			Set<String> envIds = byExtension ? index.getOsRequirementByExt(entry) : index.getEnvironmentsByPUID(entry);
			if (envIds != null) {
				for (String envId : envIds) {
					Integer count = resultMap.get(envId);
					if (count == null)
						count = 0;
					count += 1;
					if (count > maxCount)
						maxCount = count;
					resultMap.put(envId, count);
				}
			}
		}

		log.info("propose: maxCount " + maxCount);
		for (String proposedEnv : resultMap.keySet()) {
			//only use the environments that support the most file formats, ignore worse ones
			//TODO What about file count? 5 txt, 1 json, 1 xml - currently an env that supports json + xml but not txt would be suggested
			Integer count = resultMap.get(proposedEnv);
			if (count != maxCount)
				continue;

			envIdResults.add(proposedEnv);
		}

		if(maxCount > 0){
			//Checks if the provided types can be rendered by an operating system where no additional software is required
			List<OperatingSystemInformation> suggestedOS = byExtension ? index.getOSforExtensions(values, maxCount) : index.getOSforPUID(values, maxCount);

			for (var os : suggestedOS) {
				osSuggestion.put(os.getId(), os.getLabel());
			}
		}

		// "returns":
		// envIds is filled with suitable envs that support as many types as possible
		// osSuggestions: Map of OS ID + Label for suitable Operating Systems

	}


	@Override
	public Proposal execute() throws Exception
	{
		// Update the index, if needed!
		indexHandle.refresh();

		Collection<String> suitableEnvironments = new HashSet<String>();
		final Map<String, String> osSuggestion = new HashMap<>();
		final ImageIndex index = indexHandle.get();

		proposeByPUID(index, suitableEnvironments, osSuggestion);
		if (suitableEnvironments.isEmpty() && osSuggestion.isEmpty()) {
			proposeByExtension(index, suitableEnvironments, osSuggestion);
		}

		suitableEnvironments = sorter.sort(suitableEnvironments);

		log.info("Propose algorithm finished! " + suitableEnvironments.size() + " suitable environment(s) found:");
		log.info("Suggested OS: " + DataUtils.json().writer(true).writeValueAsString(osSuggestion));


		return new Proposal(suitableEnvironments, osSuggestion);
	}
}
