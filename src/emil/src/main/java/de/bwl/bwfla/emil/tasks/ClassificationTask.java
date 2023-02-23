package de.bwl.bwfla.emil.tasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openslx.eaas.common.databind.DataUtils;
import com.openslx.eaas.imagearchive.ImageArchiveClient;
import de.bwl.bwfla.common.datatypes.identification.DiskType;
import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.common.services.security.UserContext;
import de.bwl.bwfla.common.taskmanager.BlockingTask;
import de.bwl.bwfla.emil.DatabaseEnvironmentsAdapter;
import de.bwl.bwfla.emil.EmilEnvironmentRepository;
import de.bwl.bwfla.emil.ObjectClassification;
import de.bwl.bwfla.emil.datatypes.EmilEnvironment;
import de.bwl.bwfla.emil.datatypes.EmilObjectEnvironment;
import de.bwl.bwfla.emil.datatypes.EnvironmentInfo;
import de.bwl.bwfla.emil.datatypes.rest.ClassificationResult;
import de.bwl.bwfla.emucomp.api.FileCollection;
import de.bwl.bwfla.emucomp.api.FileCollectionEntry;
import de.bwl.bwfla.imageclassifier.client.ClassificationEntry;
import de.bwl.bwfla.imageclassifier.client.Identification;
import de.bwl.bwfla.imageclassifier.client.IdentificationRequest;
import de.bwl.bwfla.imageclassifier.client.ImageClassifier;
import de.bwl.bwfla.imageproposer.client.ImageProposer;
import de.bwl.bwfla.imageproposer.client.Proposal;
import de.bwl.bwfla.imageproposer.client.ProposalRequest;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ClassificationTask extends BlockingTask<Object>
{


    private static final Logger LOG = Logger.getLogger(ClassificationTask.class.getName());

    public ClassificationTask(ClassifyObjectRequest req)
    {
        this.request = req;
        this.emilEnvRepo = req.metadata;
        this.envHelper = req.environments;
        this.imagearchive = req.imagearchive;
        this.imageClassifier = req.classification.imageClassifier();
        this.imageProposer = req.classification.imageProposer();
        this.classification = req.classification;
    }

    private final EmilEnvironmentRepository emilEnvRepo;
    private final ClassifyObjectRequest request;

    @Deprecated
    private final DatabaseEnvironmentsAdapter envHelper;
    private final ImageArchiveClient imagearchive;
    private final ImageClassifier imageClassifier;
    private final ImageProposer imageProposer;
    private final ObjectClassification classification;

    public static class ClassifyObjectRequest
    {
        public ObjectClassification classification;

        @Deprecated
        public DatabaseEnvironmentsAdapter environments;
        public ImageArchiveClient imagearchive;
        public EmilEnvironmentRepository metadata;
        public ClassificationResult input;
        public FileCollection fileCollection;
        public String url;
        public String filename;
        public boolean noUpdate;
        public boolean forceProposal;
        public UserContext userCtx;
    }

    private List<EnvironmentInfo> resolveEmilEnvironments(String objectId, Collection<String> proposedEnvironments) throws IOException, BWFLAException {

        HashMap<String, List<EmilEnvironment>> envMap = new HashMap<>();
        List<EmilEnvironment> environments = emilEnvRepo.getEmilEnvironments(request.userCtx)
                .collect(Collectors.toList());
//        if (environments != null && environments.size() == 0) {
//             FIXME
//             we need to call EmilEnvironmentData.init() here
//        }

        //keep this check for the future
        //if the imagearchive is null (should not happen anymore), the task will fail without any error logs (=a lot of debugging)
        if(imagearchive == null){
            throw new BWFLAException("Could not connect to ImageArchive...");
        }

        HashSet<String> knownEnvironments = new HashSet<>();
        for (String envId : proposedEnvironments) {
            final var exists = imagearchive.api()
                    .v2()
                    .machines()
                    .exists(envId);

            if (!exists)
                continue;

            List<EmilEnvironment> emilEnvironments = emilEnvRepo.getChildren(envId, environments, request.userCtx);
            List<EmilEnvironment> resultList = new ArrayList<>();
            for(EmilEnvironment emilEnv : emilEnvironments) {
                if(emilEnv instanceof EmilObjectEnvironment) // do this later
                    continue;
                if (emilEnv != null) {
                    if(!knownEnvironments.contains(emilEnv.getEnvId())) {
                        if(!proposedEnvironments.contains(emilEnv.getEnvId())) {
                            resultList.add(emilEnv);
                            knownEnvironments.add(emilEnv.getEnvId());
                        }
                        else {
                            // LOG.info("proposed envs contains: " + emilEnv.getEnvId() + " skipp env");
                            EmilEnvironment _env = emilEnvRepo.getEmilEnvironmentById(envId, request.userCtx);
                            if(_env instanceof EmilObjectEnvironment)
                                break;

                            if(_env != null && emilEnvRepo.isEnvironmentVisible(_env))
                            {
                                resultList.add(_env);
                            }
                            break;
                        }
                    }
                }
            }
            envMap.put(envId, resultList);
        }

        final Function<EmilObjectEnvironment, EnvironmentInfo> objEnvToInfo = (objEnv) -> {
            EnvironmentInfo ei = new EnvironmentInfo(objEnv.getEnvId(), objEnv.getTitle());
            // LOG.info("found oe: " + objEnv.getTitle());
            ei.setObjectEnvironment(true);
            return ei;
        };

        final var result = emilEnvRepo.getEmilObjectEnvironmentByObject(objectId, request.userCtx)
                .map(objEnvToInfo)
                .collect(Collectors.toList());

        for(String envId: proposedEnvironments)
        {
            List<EmilEnvironment> resolved = envMap.get(envId);
            if(resolved == null)
                continue;

            for(EmilEnvironment env : resolved)
                result.add(new EnvironmentInfo(env.getEnvId(), env.getTitle()));
        }
        // LOG.info("resolve environment: received " + proposedEnvironments + " proposed envs, resolved " + result.size());

        return result;
    }

    private ClassificationResult classifyObject(IdentificationRequest req, String fileId) throws BWFLAException{
        try {
            log.info("Classifying object: '" + fileId + "'....");
            Identification<ClassificationEntry> id = this.imageClassifier.getClassification(req, request.userCtx);

            HashMap<String, Identification.IdentificationDetails<ClassificationEntry>> data = id.getIdentificationData();
            if (data == null) {
                LOG.warning("Did not get identification data for '" + fileId + "'.");
                return new ClassificationResult();
            }

            HashMap<String, ClassificationResult.IdentificationData> fileFormats = new HashMap<>();
            HashMap<String, DiskType> mediaFormats = new HashMap<>();
            HashMap<String, List<String>> filesPerFce = new HashMap<>();

            if(req.getFileCollection()!= null){
                for (FileCollectionEntry fce : req.getFileCollection().files) {
                    setupClassificationData(data, fileFormats, mediaFormats, filesPerFce, fce.getId());
                }
            }
            else if (req.getFileName()!=null) {
                setupClassificationData(data, fileFormats, mediaFormats, filesPerFce, fileId);
            }
            log.info("Successfully classified: '" + fileId + "'.");
            return new ClassificationResult(fileId, fileFormats, mediaFormats, filesPerFce);
        }
        catch (Throwable t) {
            LOG.warning("Classification failed: " + t.getMessage());
            LOG.log(Level.SEVERE, t.getMessage(), t);
            return new ClassificationResult();
        }
    }

    private static void setupClassificationData(HashMap<String, Identification.IdentificationDetails<ClassificationEntry>> data,
                                                HashMap<String, ClassificationResult.IdentificationData> fileFormats,
                                                HashMap<String, DiskType> mediaFormats,
                                                HashMap<String, List<String>> filesPerFce,
                                                String fileId)
    {
        Identification.IdentificationDetails<ClassificationEntry> details = data.get(fileId);
        if (details == null)
            return;

        List<ClassificationResult.FileFormat> fmts = getFileFormats(details);

        List<String> files = details
                .getEntries()
                .stream()
                .flatMap((ClassificationEntry ce) -> ce.getFiles().stream())
                .collect(Collectors.toList());
        filesPerFce.put(fileId, files);

        ClassificationResult.IdentificationData d = new ClassificationResult.IdentificationData();
        d.setFileFormats(fmts);
        fileFormats.put(fileId, d);

        if (details.getDiskType() != null)
            mediaFormats.put(fileId, details.getDiskType());
    }

    private static List<ClassificationResult.FileFormat> getFileFormats(Identification.IdentificationDetails<ClassificationEntry> details)
    {
        List<ClassificationResult.FileFormat> fmts = details
                .getEntries()
                .stream()
                .map((ClassificationEntry ce) ->
                        new ClassificationResult.FileFormat(ce.getType(),
                                ce.getTypeName(),
                                ce.getCount(),
                                ce.getFromDate(),
                                ce.getToDate()))
                .collect(Collectors.toList());
        return fmts;
    }

    private ClassificationResult propose(ClassificationResult response) {
        HashMap<String, DiskType> mediaFormats = new HashMap<>();
        if(response.getMediaFormats() != null)
        {
            for(String key : response.getMediaFormats().keySet()) {
                if(response.getMediaFormats().get(key) != null)
                    mediaFormats.put(key, response.getMediaFormats().get(key));
            }
        }

        HashMap<String, List<ProposalRequest.Entry>> fileFormats = new HashMap<>();
        if(response.getFileFormatMap() != null)
        {
            for(String key : response.getFileFormatMap().keySet())
            {
                List<ProposalRequest.Entry> proposalList = new ArrayList<>();
                List<ClassificationResult.FileFormat> foundFmts = response.getFileFormatMap().get(key).getFileFormats();
                if(foundFmts == null)
                    continue;

                for(ClassificationResult.FileFormat fmt : foundFmts)
                {
                    proposalList.add(new ProposalRequest.Entry(fmt.getPuid(), fmt.getCount()));
                }
                fileFormats.put(key, proposalList);
            }
        }

        Proposal proposal = null;
        try {
            proposal = imageProposer.propose(new ProposalRequest(fileFormats, mediaFormats, response.getFiles()));
        } catch (InterruptedException e) {
            return new ClassificationResult(new BWFLAException(e));
        }

        try {
            log.info(">>>>>>>>>>>>>>>>>>>>>>>>> Got this proposal:\n" + DataUtils.json().writer(true).writeValueAsString(proposal));
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        List<EnvironmentInfo> environmentList;
        List<ClassificationResult.OperatingSystem> suggested = new ArrayList<>();;

        try {
            environmentList = resolveEmilEnvironments(response.getObjectId(), proposal.getImages());
        } catch (IOException | BWFLAException e) {
            return new ClassificationResult(new BWFLAException(e));
        }

        List<EnvironmentInfo> defaultList = new ArrayList<>();
        try {//FIXME how is "missing" the "suggested" list now, here also the os check before something is added to suggested is weird?! why?
            for (String osId : proposal.getSuggested().keySet()) {
                log.info("Checking osId: " + osId);
                ClassificationResult.OperatingSystem os = new ClassificationResult.OperatingSystem(osId, proposal.getSuggested().get(osId));
                String envId = envHelper.getDefaultEnvironment(osId);
                if (envId != null) {
                    log.info("Checking if default env " + envId + "is suitable...");
                    EmilEnvironment emilEnv = emilEnvRepo.getEmilEnvironmentById(envId, request.userCtx);
                    if (emilEnv != null) {
                        log.info("Got suitable default env: " + envId);
                        EnvironmentInfo info = new EnvironmentInfo(emilEnv.getEnvId(), emilEnv.getTitle());
                        os.setDefaultEnvironment(info);

                        EnvironmentInfo infoL = new EnvironmentInfo(emilEnv.getEnvId(), emilEnv.getTitle() + " (D)");
                        if(defaultList.stream().noneMatch(o -> o.getId().equals(infoL.getId())))
                            defaultList.add(infoL);
                    }
                }
                suggested.add(os);
            }
            if(defaultList.isEmpty())
            {
                String envId = envHelper.getDefaultEnvironment(null);
                if(envId != null)
                {
                    EmilEnvironment emilEnv = emilEnvRepo.getEmilEnvironmentById(envId, request.userCtx);
                    EnvironmentInfo infoL = new EnvironmentInfo(emilEnv.getEnvId(), emilEnv.getTitle() + " (D)");
                    defaultList.add(infoL);
                }
            }
        }
        catch (BWFLAException e)
        {
            LOG.log(Level.SEVERE, e.getMessage(), e);
        }
        response.setSuggested(suggested);

        if(defaultList.size() > 0) { //FIXME this prefers default envs (which makes sense up to the point where other envs could be better!!!)
            for(EnvironmentInfo info : environmentList)
            {
                if(info.isObjectEnvironment()) {
                    LOG.info("adding oe to default list.");
                    defaultList.add(0, info);
                }
            }
            response.setEnvironmentList(defaultList);
        }
        else
            response.setEnvironmentList(environmentList);

//=======
//        HashMap<String, RelatedQIDS> qidsHashMap = new HashMap<>();
//        environmentList.forEach(env -> {
//            String os = null;
//            try {
//                os =  ((MachineConfiguration) envHelper.getEnvironmentById(env.getId())).getOperatingSystemId();
//
//                if(os != null) {
//                    // sanitze: remove ':'
//                    os = os.replace(':', '_');
//                    qidsHashMap.put(env.getId(), QIDsFinder.findFollowingAndFollowedQIDS(os));
//                }
//            } catch (BWFLAException e) {
//                e.printStackTrace();
//            }
//        });
//
//        response.setEnvironmentList(environmentList);
//>>>>>>> master
        try {
            LOG.info("Finished proposing environments, returning: \n" + DataUtils.json().writer(true).writeValueAsString(response));
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return response;
    }


    @Override
    protected ClassificationResult execute() throws Exception {

        ClassificationResult result = request.input;

        if(request.noUpdate)
            return result;

        if(request.fileCollection != null) {
            if (request.input == null || request.input.getMediaFormats().size() == 0){
                request.input = classifyObject(new IdentificationRequest(request.fileCollection, null),
                        request.fileCollection.id);
            }

        }
        else if(request.url != null && request.filename != null)
        {
            request.input = classifyObject(new IdentificationRequest(request.url, request.filename), request.filename);
            return propose(request.input);
        }
        else {
            throw new BWFLAException("invalid request");
        }

        if(request.forceProposal || request.input.getEnvironmentList().size() == 0)
            result = propose(request.input);

        classification.save(result);
        return result;
    }

}
