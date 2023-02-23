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

	private Set<String> getExtensions() throws JAXBException {
		Set<String> extensions = new HashSet<>();
		for(String key : request.getMediaFormats().keySet()) {
			if (request.getMediaFormats().get(key) != null) {
				DiskType diskType = request.getMediaFormats().get(key);
				log.info(diskType.JSONvalue(true));

				String fileName = diskType.getLocalAlias();
				if(fileName == null)
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
		if(index < 0)
			return null;
		String ext = fileName.substring(index + 1);
		return ext;
	}

	private void proposeByExtension(ImageIndex index,
									Collection<String> images,
									Map<String, String> missing) throws JAXBException {
		int maxCount = 0;
		HashMap<String, Integer> resultMap = new HashMap<>(); // count hits per environment

		log.info("Could not find suitable images through PUIDs, now trying file extensions...");

		Set<String> extensions = getExtensions();
		if(getExtensions().isEmpty()){
			log.info("Did not get any extensions, no results will be added.");
			return;
		}

		for (String ext : extensions) {

			log.info("Checking extension: " + ext);

			Set<String> envIds = index.getEnvironmentsByExt(ext);
			if (envIds != null) {
				for (String envId : envIds) {
					log.info("Found suitable env! " + envId);
					Integer count = resultMap.get(envId);
					if (count == null)
						count = 0;
					count += 1;
					if (count > maxCount)
						maxCount = count;
					resultMap.put(envId, count);
				}
			}

			log.info("Getting OSs for: " + ext);
			Set<String> os = index.getOsRequirementByExt(ext);
			if (os != null) {
				for (String osId : os) {
					log.info("\t Checking (ext): " + osId);
					OperatingSystemInformation operatingSystemInformation = index.getOperatingSystemInfo(osId);
					if(operatingSystemInformation != null)
						missing.put(operatingSystemInformation.getId(), operatingSystemInformation.getLabel());
				}
			}
		}

		log.info("propose: maxCount " + maxCount);
		for(String proposedEnv : resultMap.keySet())
		{
			Integer count = resultMap.get(proposedEnv);
			if(count != maxCount)
				continue;

			images.add(proposedEnv);
		}
	}

	private void proposeByPUID(ImageIndex index,
								  Collection<String> images,
								  Map<String, String> missing)
	{
		int maxCount = 0;
		HashMap<String, Integer> resultMap = new HashMap<>(); // count hits per environment
		log.info("Running propose algorithm...");

		for(String key : request.getFileFormats().keySet()) {
			for (ProposalRequest.Entry entry : request.getFileFormats().get(key)) {
				Set<String> envIds = index.getEnvironmentsByPUID(entry.getType());
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

				log.info("Getting OSs for: (puid)" + entry.getType());
				Set<String> os = index.getOsRequirementByPUID(entry.getType());
				if (os != null) {
					for (String osId : os) {
						log.info("\t Checking: " + osId); //FIXME this is a problem
						OperatingSystemInformation operatingSystemInformation = index.getOperatingSystemInfo(osId);
						if(operatingSystemInformation != null)
							missing.put(operatingSystemInformation.getId(), operatingSystemInformation.getLabel());
					}
				}
			}
		}

		log.info("propose: maxCount " + maxCount);
		for(String proposedEnv : resultMap.keySet())
		{
			Integer count = resultMap.get(proposedEnv);
			if(count != maxCount)
				continue;

			images.add(proposedEnv);
		}
	}

	@Override
	public Proposal execute() throws Exception
	{
		// Update the index, if needed!
		// uncommenting this fixes environments not considered by proposer before restarting the system
		indexHandle.refresh();

		Collection<String> images = new HashSet<String>();
		final Map<String, String> missing = new HashMap<>();
		final ImageIndex index = indexHandle.get();
//		int numMissingFormats = 0;
//		int numFoundFormats = 0;

		proposeByPUID(index, images, missing);
		if(images.isEmpty() && missing.isEmpty())
		{
			proposeByExtension(index, images, missing);
		}

		images = sorter.sort(images);
		
		log.info("Propose algorithm finished! " + images.size() + " suitable environment(s) found:");

		log.info("Missing: " + DataUtils.json().writer(true).writeValueAsString(missing));

		if (!missing.isEmpty()) {
			log.info("No suitable environments found for the following format(s):");
			for(var miss : missing.entrySet()){
				log.info(">>> " + miss.getKey() + ":" + miss.getValue());
			}
		}

		return new Proposal(images, missing);
	}

	//	private  Drive.DriveType getMediaType(DiskType type)
//	{
//		Set<String> wikidataSet = new HashSet<>();
//
//		if(type.getContent() == null)
//			return null;
//
//		for (Content c : type.getContent())
//		{
//			if(c.getWikidata() != null)
//				wikidataSet.addEnvironmentWithPUID(c.getWikidata());
//			log.info("found " + c.getType() + "(" + c.getWikidata()+ ")");
//		}
//
//		if(wikidataSet.contains("Q3063042")) {
//			log.info("found floppy");
//			return Drive.DriveType.FLOPPY;
//		}
//
//		if(wikidataSet.contains("Q55336682")) {
//			log.info("found iso");
//			return Drive.DriveType.CDROM;
//		}
//		return null;
//	}
}
