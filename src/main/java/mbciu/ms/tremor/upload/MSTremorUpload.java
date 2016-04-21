package mbciu.ms.tremor.upload;

import java.io.Console;
import java.io.File;
import java.io.FileFilter;
import java.io.OutputStream;

import arc.archive.ArchiveOutput;
import arc.archive.ArchiveRegistry;
import arc.mf.client.RemoteServer;
import arc.mf.client.ServerClient;
import arc.mf.client.archive.Archive;
import arc.streams.StreamCopy.AbortCheck;
import arc.xml.XmlDoc;
import arc.xml.XmlStringWriter;
import mbciu.ms.tremor.upload.util.CidUtil;

public class MSTremorUpload {

    public static final String DEFAULT_HOST = "daris-1.cloud.unimelb.edu.au";
    public static final int DEFAULT_PORT = 443;
    public static final String DEFAULT_TRANSPORT = "https";

    public static final String DEFAULT_STEP = "7";

    public static void main(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        String transport = DEFAULT_TRANSPORT;
        String auth = null;
        String domain = null;
        String user = null;
        String password = null;
        String pid = null;
        String studyName = null;
        String methodStep = DEFAULT_STEP;
        String datasetName = null;
        File dir = null;
        try {
            for (int i = 0; i < args.length;) {
                if (args[i].equals("--host")) {
                    host = args[i + 1];
                    i += 2;
                } else if (args[i].equals("--port")) {
                    port = Integer.parseInt(args[i + 1]);
                    i += 2;
                } else if (args[i].equals("--transport")) {
                    transport = args[i + 1];
                    i += 2;
                } else if (args[i].equals("--auth")) {
                    auth = args[i + 1];
                    i += 2;
                } else if (args[i].equals("--pid")) {
                    pid = args[i + 1];
                    i += 2;
                } else if (args[i].equals("--study-name")) {
                    studyName = args[i + 1];
                    i += 2;
                } else if (args[i].equals("--method-step")) {
                    methodStep = args[i + 1];
                    i += 2;
                } else if (args[i].equals("--dataset-name")) {
                    datasetName = args[i + 1];
                    i += 2;
                } else {
                    if (dir == null) {
                        dir = new File(args[i]);
                        if (!dir.exists()) {
                            throw new IllegalArgumentException("Directory "
                                    + dir.getPath() + " does not exist.");
                        }
                    } else {
                        throw new IllegalArgumentException(
                                "More than one directories are specified. Can only upload one directory.");
                    }
                    i++;
                }
            }
            if (dir == null) {
                throw new IllegalArgumentException(
                        "No data directory is specified.");
            }
            if (host == null) {
                throw new IllegalArgumentException("Missing argument: --host");
            }
            if (auth == null) {
                Console console = System.console();
                domain = console.readLine("[%s]: ", "Domain");
                user = console.readLine("[%s]: ", "User");
                password = new String(
                        console.readPassword("[%s]: ", "Password"));
            } else {
                String[] parts = auth.split(",");
                if (parts.length == 3) {
                    domain = parts[0].trim();
                    user = parts[1].trim();
                    password = parts[2].trim();
                } else {
                    throw new IllegalArgumentException(
                            "Invalid --auth argument: " + auth);
                }
            }
            if (pid == null) {
                throw new IllegalArgumentException(
                        "Missing argument: --pid is " + pid);
            }
            int pidDepth = CidUtil.getDepth(pid);
            if (pidDepth < CidUtil.SUBJECT_ID_DEPTH
                    || pidDepth > CidUtil.STUDY_ID_DEPTH) {
                throw new IllegalArgumentException("Invalid argument: --pid is "
                        + pid
                        + ". Expects the citable id of subject, ex-method, or study.");
            }
            if (pidDepth < CidUtil.STUDY_ID_DEPTH) {
                if (studyName == null) {
                    throw new IllegalArgumentException(
                            "Missing argument: --study-name");
                }
                if (methodStep == null) {
                    throw new IllegalArgumentException(
                            "Missing argument: --method-step");
                }
            }
            if (datasetName == null) {
                datasetName = dir.getName();
            }
        } catch (Throwable e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            System.exit(1);
        }
        boolean useHttp = transport.startsWith("http")
                || transport.startsWith("HTTP");
        boolean encrypt = transport.equalsIgnoreCase("HTTPS");
        RemoteServer server = new RemoteServer(host, port, useHttp, encrypt);
        ServerClient.Connection cxn = null;
        try {
            try {
                cxn = server.open();
                cxn.connect(domain, user, password);
                upload(cxn, dir, pid, studyName, methodStep, datasetName);
            } finally {
                if (cxn != null) {
                    cxn.closeAndDiscard();
                }
            }
        } catch (Throwable e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static String upload(ServerClient.Connection cxn, File dir, String pid,
            String studyName, String methodStep, String datasetName)
                    throws Throwable {
        XmlDoc.Element pae = getAssetMeta(cxn, pid);
        String ptype = pae.value("meta/daris:pssd-object/type");
        if (!("subject".equals(ptype) || "ex-method".equals(ptype)
                || "study".equals(ptype))) {
            throw new Exception("Invalid pid argument: " + pid
                    + ". The object associated with " + pid
                    + " is not a subject, ex-method or study object.");
        }
        String studyCid = null;
        if ("study".equals(ptype)) {
            studyCid = pid;
            if (studyName == null) {
                studyName = pae.value("meta/daris:pssd-object/name");
            } else {
                if (!studyName
                        .equals(pae.value("meta/daris:pssd-object/name"))) {
                    throw new Exception(
                            "The name of the specified parent study " + pid
                                    + " is not the same as the specified --study-name "
                                    + studyName
                                    + ". If you specify a study id as the --pid, you do not need to specify --study-name argument.");
                }
            }
        } else {
            if (studyName == null) {
                throw new Exception("No study name is specified.");
            }
            studyCid = findOrCreateStudy(cxn, pid, ptype, studyName,
                    methodStep);
        }
        String datasetCid = findDatasetByNameAndSourcePath(cxn, studyCid,
                datasetName, dir.getAbsolutePath());
        if (datasetCid != null) {
            System.err.println("Dataset " + datasetCid + " (name: '"
                    + datasetName + "') uploaded from '" + dir.getAbsolutePath()
                    + "' already exist.");
        } else {
            datasetCid = createDataset(cxn, studyCid, datasetName, dir);
            System.out.println("Created dataset " + datasetCid + ".");
        }
        return datasetCid;
    }

    static String findOrCreateStudy(ServerClient.Connection cxn, String pid,
            String ptype, String studyName, String methodStep)
                    throws Throwable {
        String studyCid = null;
        XmlDoc.Element studyAE = findStudyByName(cxn, pid, studyName);
        if (studyAE != null) {
            studyCid = studyAE.value("cid");
            System.out.println("Found study " + studyCid + ".");
        } else {
            String exMethodCid = null;
            if ("ex-method".equals(ptype)) {
                exMethodCid = pid;
            } else {
                assert "subject".equals(ptype);
                exMethodCid = cxn
                        .execute("asset.query",
                                "<action>get-cid</action><size>1</size><where>cid in '"
                                        + pid
                                        + "' and model='om.pssd.ex-method'</where>")
                        .value("cid");
                if (exMethodCid == null) {
                    throw new Exception("No ex-method found in subject " + pid);
                }

            }
            studyCid = createStudy(cxn, exMethodCid, studyName, methodStep);
            System.out.println("Created study " + studyCid + ".");
        }
        return studyCid;
    }

    static XmlDoc.Element findStudyByName(ServerClient.Connection cxn,
            String pid, String studyName) throws Throwable {
        return cxn.execute("asset.query",
                "<action>get-meta</action><size>1</size><where>cid starts with '"
                        + pid
                        + "' and model='om.pssd.study' and xpath(daris:pssd-object/name)='"
                        + studyName + "'</where>")
                .element("asset");
    }

    static String createStudy(ServerClient.Connection cxn, String pid,
            String studyName, String methodStep) throws Throwable {
        XmlStringWriter w = new XmlStringWriter();
        w.add("pid", pid);
        w.add("fillin", true);
        w.add("step", methodStep);
        w.add("name", studyName);
        w.add("description", studyName);
        return cxn.execute("om.pssd.study.create", w.document()).value("id");
    }

    static String findDatasetByNameAndSourcePath(ServerClient.Connection cxn,
            String studyCid, String datasetName, String sourcePath)
                    throws Throwable {
        XmlStringWriter w = new XmlStringWriter();
        w.add("where",
                "cid in '" + studyCid
                        + "' and model='om.pssd.dataset' and xpath(daris:pssd-object/name)='"
                        + datasetName + "' and xpath(mf-note/note)='"
                        + sourcePath + "'");
        w.add("action", "get-cid");
        return cxn.execute("asset.query", w.document()).value("cid");
    }

    static String createDataset(ServerClient.Connection cxn, String studyCid,
            String datasetName, File dir) throws Throwable {
        XmlDoc.Element studyAE = cxn
                .execute("asset.get", "<cid>" + studyCid + "</cid>")
                .element("asset");
        String methodStep = studyAE.value("meta/daris:pssd-study/method/@step");
        String subjectCid = CidUtil.getParentCid(studyCid, 2);
        String exMethodCid = CidUtil.getParentCid(studyCid);
        XmlStringWriter w = new XmlStringWriter();
        w.add("pid", studyCid);
        w.add("name", datasetName);
        w.add("description", datasetName);
        w.add("fillin", true);
        w.add("filename", datasetName.replace(' ', '_') + ".aar");
        w.add("type", "application/arc-archive");
        w.push("subject");
        w.add("id", subjectCid);
        w.pop();
        w.push("method");
        w.add("id", exMethodCid);
        w.add("step", methodStep);
        w.pop();
        w.add("ctype", "application/arc-archive");
        w.push("meta");
        w.push("mf-note");
        w.add("note", dir.getAbsolutePath());
        w.pop();
        w.pop();
        return cxn.execute("om.pssd.dataset.primary.create", w.document(),
                createInput(dir), null).value("id");
    }

    static XmlDoc.Element getAssetMeta(ServerClient.Connection cxn, String cid)
            throws Throwable {
        return cxn.execute("asset.get", "<cid>" + cid + "</cid>")
                .element("asset");
    }

    static ServerClient.Input createInput(final File dir) throws Throwable {
        return new ServerClient.GeneratedInput("application/arc-archive", "aar",
                dir.getAbsolutePath(), -1, null) {
            @Override
            protected void copyTo(OutputStream out, AbortCheck abort)
                    throws Throwable {
                Archive.declareSupportForAllTypes();
                ArchiveOutput ao = ArchiveRegistry.createOutput(out,
                        "application/arc-archive", 6, null);
                try {
                    aar(dir.listFiles(new FileFilter() {

                        @Override
                        public boolean accept(File f) {
                            return !f.getName().equals(".DS_Store");
                        }
                    }), dir.getParentFile().getAbsolutePath(), ao);
                } finally {
                    ao.close();
                }
            }

        };
    }

    static void aar(File[] files, String baseDir, ArchiveOutput output)
            throws Throwable {
        for (File f : files) {
            String name = f.getAbsolutePath();
            if (name.startsWith(baseDir)) {
                name = name.substring(baseDir.length());
            }
            if (name.startsWith(System.getProperty("file.separator"))) {
                name = name.substring(1);
            }
            if (f.isDirectory()) {
                aar(f.listFiles(), baseDir, output);
            } else {
                System.out.println("Uploading \"" + f.getAbsolutePath() + "\"...");
                output.add(null, name, f);
            }
        }
    }

    static void printUsage() {
        System.out.println("Usage: ms-tremor-upload [options] <dir>");
        System.out.println("Options:");
        System.out.println(
                "    --host <host>                 Mediaflux server host.");
        System.out.println(
                "    --port <port>                 Mediaflux server port.");
        System.out.println(
                "    --transport <transport>       Mediaflux server transport.");
        System.out.println(
                "    --auth <domain,user,password> User authentication details.");
        System.out.println(
                "    --pid <pid>                   The parent subject id.");
        System.out.println(
                "    --study-name <study-name>     The parent study name. It will auto-create the study if not exist.");
        System.out.println(
                "    --method-step <step>          The method step. It is required if study need to be auto-created.");
        System.out.println(
                "    --dataset-name <dataset-name> The name of the dataset. If not specified, use the local directory name as dataset name.");
    }
}
