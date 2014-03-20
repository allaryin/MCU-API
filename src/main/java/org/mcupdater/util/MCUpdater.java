package org.mcupdater.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.mcupdater.*;
import org.mcupdater.api.Version;
import org.mcupdater.downloadlib.*;
import org.mcupdater.instance.FileInfo;
import org.mcupdater.instance.Instance;
import org.mcupdater.model.*;
import org.mcupdater.mojang.AssetIndex;
import org.mcupdater.mojang.AssetIndex.Asset;
import org.mcupdater.mojang.Library;
import org.mcupdater.mojang.MinecraftVersion;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

//import j7compat.Files;
//import java.nio.file.StandardCopyOption;
//import java.nio.file.StandardOpenOption;
//import j7compat.Path;

public class MCUpdater {
	//public static final ResourceBundle Customization = ResourceBundle.getBundle("customization");
	//private List<Module> modList = new ArrayList<Module>();
	private final Path MCFolder;
	private Path archiveFolder;
	private Path instanceRoot;
	private MCUApp parent;
	private final String sep = System.getProperty("file.separator");
	public MessageDigest md5;
	public ImageIcon defaultIcon;
	private String newestMC = "";
	private final Map<String,String> versionMap = new HashMap<>();
	public static Logger apiLogger;
	//private Path lwjglFolder;
	private int timeoutLength = 5000;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();	
	private static MCUpdater INSTANCE;

	public static File getJarFile() {
		try {
			return new File(MCUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (URISyntaxException e) {
			apiLogger.log(Level.SEVERE, "Error getting MCUpdater JAR URI", e);
		}
		return null;
	}
	
	public static MCUpdater getInstance(File file) {
		if( INSTANCE == null ) {
			INSTANCE = new MCUpdater(file);
		}
		return INSTANCE;
	}
	
	public static MCUpdater getInstance() {
		if( INSTANCE == null ) {
			INSTANCE = new MCUpdater(null);
		}
		return INSTANCE;		
	}
	
	public static String cpDelimiter() {
		String osName = System.getProperty("os.name");
		if (osName.startsWith("Windows")) {
			return ";";
		} else {
			return ":";
		}
	}
	
	private MCUpdater(File desiredRoot)
	{
		apiLogger = Logger.getLogger("MCU-API");
		apiLogger.setLevel(Level.ALL);
		//String[] nativeNames;
		//String nativePrefix;
		if(System.getProperty("os.name").startsWith("Windows"))
		{
			MCFolder = new File(System.getenv("APPDATA")).toPath().resolve(".minecraft");
			archiveFolder = new File(System.getenv("APPDATA")).toPath().resolve(".MCUpdater");
			//nativePrefix = "lwjgl-2.9.0/native/windows/";
			//nativeNames = new String[] {"jinput-dx8.dll","jinput-dx8_64.dll","jinput-raw.dll","jinput-raw_64.dll","lwjgl.dll","lwjgl64.dll","OpenAL32.dll","OpenAL64.dll"};
		} else if(System.getProperty("os.name").startsWith("Mac"))
		{
			MCFolder = new File(System.getProperty("user.home")).toPath().resolve("Library").resolve("Application Support").resolve("minecraft");
			archiveFolder = new File(System.getProperty("user.home")).toPath().resolve("Library").resolve("Application Support").resolve("MCUpdater");
			//nativePrefix = "lwjgl-2.9.0/native/macosx/";
			//nativeNames = new String[] {"libjinput-osx.jnilib","liblwjgl.jnilib","openal.dylib"};
		}
		else
		{
			MCFolder = new File(System.getProperty("user.home")).toPath().resolve(".minecraft");
			archiveFolder = new File(System.getProperty("user.home")).toPath().resolve(".MCUpdater");
			//nativePrefix = "lwjgl-2.9.0/native/linux/";
			//nativeNames = new String[] {"libjinput-linux.so","libjinput-linux64.so","liblwjgl.so","liblwjgl64.so","libopenal.so","libopenal64.so"};
		}
		if (!(desiredRoot == null)) {
			archiveFolder = desiredRoot.toPath();
		}
		//lwjglFolder = this.archiveFolder.resolve("LWJGL");
		try {
			FileHandler apiHandler = new FileHandler(archiveFolder.resolve("MCU-API.log").toString(), 0, 3);
			apiHandler.setFormatter(new FMLStyleFormatter());
			apiLogger.addHandler(apiHandler);
			
		} catch (SecurityException | IOException e1) {
			e1.printStackTrace(); // Will only be thrown if there is a problem with logging.
		}
		try {
			md5 = MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			apiLogger.log(Level.SEVERE, "No MD5 support!", e);
		}

		try {
			defaultIcon = new ImageIcon(MCUpdater.class.getResource("/minecraft.png"));
		} catch( NullPointerException e ) {
			_debug( "Unable to load default icon?!" );
			defaultIcon = new ImageIcon(new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB));
		}
		// configure the download cache
		try {
			DownloadCache.init(archiveFolder.resolve("cache").toFile());
		} catch (IllegalArgumentException e) {
			_debug( "Suppressed attempt to re-init download cache?!" );
		}
		try {
			long start = System.currentTimeMillis();
			URL md5s = new URL("http://files.mcupdater.com/md5.dat");
			URLConnection md5Con = md5s.openConnection();
			md5Con.setConnectTimeout(this.timeoutLength);
			md5Con.setReadTimeout(this.timeoutLength);
			InputStreamReader input = new InputStreamReader(md5Con.getInputStream());
			BufferedReader buffer = new BufferedReader(input);
			String currentLine;
			while(true){
				currentLine = buffer.readLine();
				if(currentLine != null){
					String entry[] = currentLine.split("\\|");
					versionMap.put(entry[0], entry[1]);
					newestMC = entry[1]; // Most recent entry in md5.dat is the current release
				} else {
					break;
				}
			}
			buffer.close();
			input.close();
			apiLogger.fine("Took "+(System.currentTimeMillis()-start)+"ms to load md5.dat");
			apiLogger.fine("newest Minecraft in md5.dat: " + newestMC);
		} catch (MalformedURLException e) {
			apiLogger.log(Level.SEVERE, "Bad URL", e);
		} catch (IOException e) {
			apiLogger.log(Level.SEVERE, "I/O Error", e);
		}
		/* Download LWJGL
		File tempFile = this.archiveFolder.resolve("lwjgl-2.9.0.zip").toFile();
		if (!tempFile.exists()) {
			try {
				String jarPrefix = "lwjgl-2.9.0/jar/";
				String[] jarNames = new String[] {"lwjgl.jar","lwjgl_util.jar","jinput.jar"};
				
				URL lwjglURL = new URL("http://sourceforge.net/projects/java-game-lib/files/Official%20Releases/LWJGL%202.9.0/lwjgl-2.9.0.zip/download");
				apiLogger.info("Downloading " + lwjglURL.getPath());
				FileUtils.copyURLToFile(lwjglURL, tempFile);
				Path nativePath = lwjglFolder.resolve("natives");
				Files.createDirectories(nativePath);
				ZipFile zf = new ZipFile(tempFile);
				ZipEntry entry;
				for (int index=0; index < jarNames.length; index++) {
					entry = zf.getEntry(jarPrefix + jarNames[index]);
					File outFile = lwjglFolder.resolve(jarNames[index]).toFile();
					apiLogger.finest("   Extract: " + outFile.getPath());
					FileOutputStream fos = new FileOutputStream(outFile);
					InputStream zis = zf.getInputStream(entry);

					int len;
					byte[] buf = new byte[1024];
					while((len = zis.read(buf, 0, 1024)) > -1) {
						fos.write(buf, 0, len);
					}

					fos.close();
					zis.close();
				}
				for (int index=0; index < nativeNames.length; index++) {
					entry = zf.getEntry(nativePrefix + nativeNames[index]);
					File outFile = nativePath.resolve(nativeNames[index]).toFile();
					apiLogger.finest("   Extract: " + outFile.getPath());
					FileOutputStream fos = new FileOutputStream(outFile);
					InputStream zis = zf.getInputStream(entry);

					int len;
					byte[] buf = new byte[1024];
					while((len = zis.read(buf, 0, 1024)) > -1) {
						fos.write(buf, 0, len);
					}

					fos.close();
					zis.close();
				}
				zf.close();
				
			} catch (MalformedURLException e) {
				apiLogger.log(Level.SEVERE, "Bad URL", e);
			} catch (IOException e) {
				apiLogger.log(Level.SEVERE, "I/O Error", e);
			}
		}
		*/
	}
	
	public MCUApp getParent() {
		return parent;
	}

	public void setParent(MCUApp parent) {
		this.parent = parent;
	}

	public void writeServerList(List<ServerList> serverlist)
	{
		try
		{
			archiveFolder.toFile().mkdirs();
			BufferedWriter writer = Files.newBufferedWriter(archiveFolder.resolve("mcuServers.dat"), StandardCharsets.UTF_8);
			
			Iterator<ServerList> it = serverlist.iterator();
			
			Set<String> urls = new HashSet<>();
			while(it.hasNext())
			{
				ServerList entry = it.next();
				urls.add(entry.getPackUrl());
			}
			for (String url : urls) {
				writer.write(url);
				writer.newLine();
			}
			
			writer.close();
		}
		catch( IOException x)
		{
			apiLogger.log(Level.SEVERE, "I/O Error", x);
		}
	}
	
	public List<Backup> loadBackupList() {
		List<Backup> bList = new ArrayList<>();
		try {
			BufferedReader reader = Files.newBufferedReader(archiveFolder.resolve("mcuBackups.dat"), StandardCharsets.UTF_8);
			
			String entry = reader.readLine();
			while(entry != null) {
				String[] ele = entry.split("~~~~~");
				bList.add(new Backup(ele[0], ele[1]));
				entry = reader.readLine();
			}
			reader.close();
			return bList;
			
		} catch(FileNotFoundException notfound) {
			apiLogger.log(Level.SEVERE, "File not found", notfound);
		} catch(IOException ioe) {
			apiLogger.log(Level.SEVERE, "I/O Error", ioe);		
		}
		return bList;
	}
	
	public void writeBackupList(List<Backup> backupList) {
		try {
			BufferedWriter writer = Files.newBufferedWriter(archiveFolder.resolve("mcuBackups.dat"), StandardCharsets.UTF_8);

			for (Backup entry : backupList) {
				writer.write(entry.getDescription() + "~~~~~" + entry.getFilename());
				writer.newLine();
			}
			
			writer.close();
		} catch(IOException ioe) {
			apiLogger.log(Level.SEVERE, "I/O Error", ioe);
		}
	}
	
	public List<ServerList> loadServerList(String defaultUrl)
	{
		List<ServerList> slList = new ArrayList<>();
		try
		{
			Set<String> urls = new HashSet<>();
			urls.add(defaultUrl);
			BufferedReader reader = Files.newBufferedReader(archiveFolder.resolve("mcuServers.dat"), StandardCharsets.UTF_8);

			String entry = reader.readLine();
			while(entry != null)
			{
				urls.add(entry);
				entry = reader.readLine();
			}
			reader.close();
			for (String serverUrl : urls) {
				try {
					Element docEle;
					Document serverHeader = ServerPackParser.readXmlFromUrl(serverUrl);
					if (!(serverHeader == null)) {
						Element parent = serverHeader.getDocumentElement();
						if (parent.getNodeName().equals("ServerPack")) {
							String mcuVersion = parent.getAttribute("version");
							NodeList servers = parent.getElementsByTagName("Server");
							for (int i = 0; i < servers.getLength(); i++) {
								docEle = (Element) servers.item(i);
								System.out.println(serverUrl + ": " + docEle.getAttribute("id"));
								ServerList sl = ServerList.fromElement(mcuVersion, serverUrl, docEle);
								slList.add(sl);
							}
						} else {
							System.out.println(serverUrl + ": *** " + parent.getAttribute("id"));
							ServerList sl = ServerList.fromElement("1.0", serverUrl, parent);
							slList.add(sl);
						}
					} else {
						apiLogger.warning("Unable to get server information from " + serverUrl);
					}
				} catch (Exception e) {
					apiLogger.log(Level.SEVERE, "General Error", e);
				}
			}
			//	String[] arrString = entry.split("\\|");
			//	slList.add(new ServerList(arrString[0], arrString[1], arrString[2]));

			return slList;

		}
		catch( FileNotFoundException notfound)
		{
			apiLogger.log(Level.SEVERE, "File not found", notfound);
		}
		catch (IOException x)
		{
			apiLogger.log(Level.SEVERE, "I/O Error", x);
		}
		return slList;
	}
		
	public Path getMCFolder()
	{
		return MCFolder;
	}

	public Path getArchiveFolder() {
		return archiveFolder;
	}

//	public Path getLWJGLFolder() {
//		return lwjglFolder;
//	}
	
	public Path getInstanceRoot() {
		return instanceRoot;
	}

	public void setInstanceRoot(Path instanceRoot) {
		this.instanceRoot = instanceRoot;
	}

	public String getMCVersion() {
		File jar = MCFolder.resolve("bin").resolve("minecraft.jar").toFile();
		byte[] hash;
		try {
			InputStream is = new FileInputStream(jar);
			hash = DigestUtils.md5(is);
			is.close();		
		} catch (FileNotFoundException e) {
			return "Not found";
		} catch (IOException e) {
			apiLogger.log(Level.SEVERE, "I/O Error", e);
			return "Error reading file";
		}
		String hashString = new String(Hex.encodeHex(hash));
		String version = lookupHash(hashString);
		if(!version.isEmpty()) {
			File backupJar = archiveFolder.resolve("mc-" + version + ".jar").toFile();
			if(!backupJar.exists()) {
				backupJar.getParentFile().mkdirs();
				copyFile(jar, backupJar);
			}
			return version;
		} else {
			return "Unknown version";
		}
	}

	private String lookupHash(String hash) {
		String out = versionMap.get(hash);
		if (out == null) {
			out = "";
		}
		return out;
	}
	
	private void copyFile(File jar, File backupJar) {
		try {
			InputStream in = new FileInputStream(jar);
			OutputStream out = new FileOutputStream(backupJar);
			
			byte[] buf = new byte[1024];
			int len;
			while ((len = in.read(buf)) > 0) {
				out.write(buf, 0, len);
			}
			in.close();
			out.close();
		} catch(IOException ioe) {
			apiLogger.log(Level.SEVERE, "I/O Error", ioe);
		}
	}

	public void saveConfig(String description) {
		File folder = MCFolder.toFile();
		List<File> contents = recurseFolder(folder, false);
		try {
			String uniqueName = UUID.randomUUID().toString() + ".zip";
			for (File entry : new ArrayList<>(contents)) {
				if (getExcludedNames(entry.getPath(), false) || entry.getPath().contains("temp")) {
					contents.remove(entry);
				}
			}
			Archive.createZip(archiveFolder.resolve(uniqueName).toFile(), contents, MCFolder, parent);
			Backup entry = new Backup(description, uniqueName);
			_debug("DEBUG: LoadBackupList");
			List<Backup> bList = loadBackupList();
			_debug("DEBUG: add");
			bList.add(entry);
			_debug("DEBUG: writeBackupList");
			writeBackupList(bList);
		} catch (IOException e) {
			apiLogger.log(Level.SEVERE, "I/O Error", e);
		}
	}

	private boolean getExcludedNames(String path, boolean forDelete) {
		if(path.contains("mcu" + sep)) {
			// never delete from the mcu folder
			return true;
		}
		if (path.contains("mods") && (path.contains(".zip") || path.contains(".jar"))) {
			// always delete mods in archive form
			return false;
		}
		if(path.contains("bin" + sep + "minecraft.jar")) {
			// always delete bin/minecraft.jar
			return false;
		}
		if(path.contains("bin" + sep)) {
			// never delete anything else in bin/
			return true;
		}
		if(path.contains("resources") && !path.contains("mods")) {
			// never delete resources unless it is under the mods directory
			return true;
		}
		if(path.contains("lib" + sep)) {
			// never delete the lib/ folder
			return true;
		}
		if(path.contains("saves")) {
			// never delete saves
			return true;
		}
		if(path.contains("screenshots")) {
			// never delete screenshots
			return true;
		}
		if(path.contains("stats")) {
			return true;
		}
		if(path.contains("texturepacks")) {
			return true;
		}
		if(path.contains("lastlogin")) {
			return true;
		}
		if(path.contains("mcuServers.dat")) {
			return true;
		}
		if(path.contains("instance.dat")) {
			return true;
		}
		if(path.contains("minecraft.jar")) {
			return true;
		}
		if(path.contains("options.txt")) {
			return forDelete;
		}
		if(path.contains("META-INF" + sep)) {
			return true;
		}
		// Temporary hardcoding of client specific mod configs (i.e. Don't clobber on update)
		if(path.contains("rei_minimap" + sep)) {
			return true;
		}
		if(path.contains("macros" + sep)) {
			return true;
		}
		if(path.contains("InvTweaks")) {
			return true;
		}
		if(path.contains("optionsof.txt")){
			return true;
		}
		if(path.contains("voxelMap")) {
			return true;
		}
		//
		return false;
	}

	private List<File> recurseFolder(File folder, boolean includeFolders)
	{
		List<File> output = new ArrayList<>();
		List<File> input = new ArrayList<>(Arrays.asList(folder.listFiles()));
		Iterator<File> fi = input.iterator();
		if(includeFolders) {
			output.add(folder);
		}
		while(fi.hasNext())
		{
			File entry = fi.next();
			if(entry.isDirectory())
			{
				List<File> subfolder = recurseFolder(entry, includeFolders);
				for (File aSubfolder : subfolder) {
					output.add(aSubfolder);
				}
			} else {
				output.add(entry);
			}
		}
		return output;
	}
	
	public void restoreBackup(File archive) {
		File folder = MCFolder.toFile();
		List<File> contents = recurseFolder(folder, true);
		for (File entry : new ArrayList<>(contents)) {
			if (getExcludedNames(entry.getPath(), true)) {
				contents.remove(entry);
			}
		}
		ListIterator<File> liClear = contents.listIterator(contents.size());
		while(liClear.hasPrevious()) { 
			File entry = liClear.previous();
			entry.delete();
		}
		Archive.extractZip(archive, MCFolder.toFile());
	}

	public boolean checkForBackup(ServerList server) {
		File jar = archiveFolder.resolve("mc-" + server.getVersion() + ".jar").toFile();
		return jar.exists();
	}
	
	public boolean installMods(final ServerList server, List<GenericModule> toInstall, List<ConfigFile> configs, boolean clearExisting, final Instance instData, ModSide side) throws FileNotFoundException {
		if (Version.requestedFeatureLevel(server.getMCUVersion(), "2.2")) {
			// Sort mod list for InJar
			Collections.sort(toInstall, new ModuleComparator());
		}
		final Path instancePath = instanceRoot.resolve(server.getServerId());
		Path binPath = instancePath.resolve("bin");
		final Path productionJar;
		//File jar = null;
		final File tmpFolder = instancePath.resolve("temp").toFile();
		tmpFolder.mkdirs();
		Set<Downloadable> jarMods = new HashSet<>();
		Set<Downloadable> generalFiles = new HashSet<>();
		DownloadQueue assetsQueue = null;
		DownloadQueue jarQueue;
		DownloadQueue generalQueue;
		DownloadQueue libraryQueue = null;
		final List<String> libExtract = new ArrayList<>();
		final Map<String,Boolean> modExtract = new HashMap<>();
		final Map<String,Boolean> keepMeta = new TreeMap<>();
		Downloadable baseJar = null;
		final MinecraftVersion version = MinecraftVersion.loadVersion(server.getVersion());
		switch (side){
		case CLIENT:
			assetsQueue = parent.submitAssetsQueue("Assets", server.getServerId(), version);
			//executor = new ThreadPoolExecutor(0, 8, 30000, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
//			jar = archiveFolder.resolve("mc-" + server.getVersion() + ".jar").toFile();
//			if(!jar.exists()) {
//				parent.log("! Unable to find a backup copy of minecraft.jar for "+server.getVersion());
//				throw new FileNotFoundException("A backup copy of minecraft.jar for version " + server.getVersion() + " was not found.");
//			}
			Set<Downloadable> libSet = new HashSet<>();
			for (Library lib : version.getLibraries()) {
				if (lib.validForOS()) {
					List<URL> urls = new ArrayList<>();
					try {
						urls.add(new URL(lib.getDownloadUrl()));
					} catch (MalformedURLException e) {
						apiLogger.log(Level.SEVERE, "Bad URL", e);
					}
					Downloadable entry = new Downloadable(lib.getName(),lib.getFilename(),"",100000,urls);
					libSet.add(entry);
					if (lib.hasNatives()) {
						libExtract.add(lib.getFilename());
					}
				}
			}
			libraryQueue = parent.submitNewQueue("Libraries", server.getServerId(), libSet, instancePath.resolve("lib").toFile(), DownloadCache.getDir());

			productionJar = binPath.resolve("minecraft.jar");
			List<URL> jarUrl = new ArrayList<>();
			try {
				jarUrl.add(new URL("https://s3.amazonaws.com/Minecraft.Download/versions/" + server.getVersion() + "/" + server.getVersion() + ".jar"));
			} catch (MalformedURLException e2) {
				apiLogger.log(Level.SEVERE, "Bad URL", e2);
			}
			String jarMD5 = "";
			for (Entry<String,String> entry : versionMap.entrySet()) {
				if (entry.getValue().equals(server.getVersion())) {
					jarMD5 = entry.getKey();
					break;
				}
			}
			baseJar = new Downloadable("Minecraft jar","0.jar",jarMD5,3000000,jarUrl);
			keepMeta.put("0.jar", Version.requestedFeatureLevel(server.getVersion(), "1.6"));
			break;
		case SERVER:
			//jar = archiveFolder.resolve("mc-server-" + server.getVersion() + ".jar").toFile();
			productionJar = instancePath.resolve("minecraft_server.jar");
			break;
		default:
			apiLogger.severe("Invalid API call to MCUpdater.installMods! (side cannot be " + side.toString() + ")");
			return false;
		}
		Boolean updateJar = clearExisting;
		if (side == ModSide.CLIENT) {
			if (!productionJar.toFile().exists()) {
				updateJar = true;
			}
		} else {
			//TODO:Server jar detection
		}			
		Iterator<GenericModule> iMods = toInstall.iterator();
		List<String> modIds = new ArrayList<>();
		int jarModCount = 0;
		while (iMods.hasNext() && !updateJar) {
			GenericModule current = iMods.next();
			if (current.getModType() == ModType.Jar) {
				FileInfo jarMod = instData.findJarMod(current.getId());
				if (jarMod == null) {
					updateJar = true;
				} else if (current.getMD5().isEmpty() || (!current.getMD5().equalsIgnoreCase(jarMod.getMD5()))) {
					updateJar = true;
				}
				jarModCount++;
			} else {
				modIds.add(current.getId());
			}
		}
		if (jarModCount != instData.getJarMods().size()) {
			updateJar = true;
		}
		if (updateJar && baseJar != null) {
			jarMods.add(baseJar);
		}
		for (FileInfo entry : instData.getInstanceFiles()) {
			if (!modIds.contains(entry.getModId())) {
				instancePath.resolve(entry.getFilename()).toFile().delete();
			}
		}
		instData.setJarMods(new ArrayList<FileInfo>());
		instData.setInstanceFiles(new ArrayList<FileInfo>());
		jarModCount = 0;
		apiLogger.info("Instance path: " + instancePath.toString());
		List<File> contents = recurseFolder(instancePath.toFile(), true);
		if (clearExisting){
			parent.setStatus("Clearing existing configuration");
			parent.log("Clearing existing configuration...");
			for (File entry : new ArrayList<>(contents)) {
				if (getExcludedNames(entry.getPath(), true)) {
					contents.remove(entry);
				}
			}
			ListIterator<File> liClear = contents.listIterator(contents.size());
			while(liClear.hasPrevious()) { 
				File entry = liClear.previous();
				entry.delete();
			}
		}
		Iterator<GenericModule> itMods = toInstall.iterator();
		final File buildJar = archiveFolder.resolve("build.jar").toFile();		
		if(buildJar.exists()) {
			buildJar.delete();
		}
		
		int modCount = toInstall.size();
		int modsLoaded = 0;
		int errorCount = 0;
		
		while(itMods.hasNext()) {
			GenericModule entry = itMods.next();
			parent.log("Mod: "+entry.getName());
			Collections.sort(entry.getPrioritizedUrls());
			String filename;
			switch (entry.getModType()) {
				case Jar:
					if (updateJar) {
						jarMods.add(new Downloadable(entry.getName(),String.valueOf(entry.getJarOrder()) + "-" + entry.getId() + ".jar",entry.getMD5(),100000,entry.getUrls()));
						keepMeta.put(String.valueOf(entry.getJarOrder()) + "-" + cleanForFile(entry.getId()) + ".jar", entry.getKeepMeta());
						instData.addJarMod(entry.getId(), entry.getMD5());
						jarModCount++;
					}
					break;
				case Coremod:
					filename = "coremods/" + cleanForFile(entry.getId()) + ".jar";
					generalFiles.add(new Downloadable(entry.getName(),filename,entry.getMD5(),100000,entry.getUrls()));
					instData.addMod(entry.getId(), entry.getMD5(), filename);
					break;
				case Library:
					filename = "lib/" + cleanForFile(entry.getId()) + ".jar";
					generalFiles.add(new Downloadable(entry.getName(),filename,entry.getMD5(),100000,entry.getUrls()));
					instData.addMod(entry.getId(), entry.getMD5(), filename);
					break;
				case Extract:
					generalFiles.add(new Downloadable(entry.getName(),cleanForFile(entry.getId()) + ".zip",entry.getMD5(),100000,entry.getUrls()));
					modExtract.put(cleanForFile(entry.getId()) + ".zip", entry.getInRoot());
					break;
				case Litemod:
					filename = entry.getPath().isEmpty() ? "mods/" + cleanForFile(entry.getId()) + ".litemod" : entry.getPath();
					generalFiles.add(new Downloadable(entry.getName(),filename,entry.getMD5(),100000,entry.getUrls()));
					instData.addMod(entry.getId(), entry.getMD5(), filename);
					break;
				case Regular:
					filename = entry.getPath().isEmpty() ? "mods/" + cleanForFile(entry.getId()) + ".jar" : entry.getPath();
					generalFiles.add(new Downloadable(entry.getName(),filename,entry.getMD5(),100000,entry.getUrls()));
					instData.addMod(entry.getId(), entry.getMD5(), filename);
					break;
				case Option:
					//TODO: Unimplemented
			}
			// 0
			modsLoaded++;
			//			parent.setProgressBar((int)( (65 / modCount) * modsLoaded + 25));
			parent.log("  Done ("+modsLoaded+"/"+modCount+")");
		}
		for (ConfigFile cfEntry : configs) {
			final File confFile = instancePath.resolve(cfEntry.getPath()).toFile();
			if (confFile.exists() && cfEntry.isNoOverwrite()) {
				continue;
			}
			List<URL> configUrl = new ArrayList<>();
			try {
				configUrl.add(new URL(cfEntry.getUrl()));
			} catch (MalformedURLException e) {
				++errorCount;
				apiLogger.log(Level.SEVERE, "General Error", e);
			}
			generalFiles.add(new Downloadable(cfEntry.getPath(), cfEntry.getPath(), cfEntry.getMD5(), 10000, configUrl));
			//1
			// save in cache for future reference
			//					if( MD5 != null ) {
			//						final boolean cached = DownloadCache.cacheFile(confFile, MD5);
			//						if( cached ) {
			//							_debug(confFile.getName() + " saved in cache");							
			//						}
			//					}
		}

		generalQueue = parent.submitNewQueue("Instance files", server.getServerId(), generalFiles, instancePath.toFile(), DownloadCache.getDir());
		jarQueue = parent.submitNewQueue("Jar build files", server.getServerId(), jarMods, tmpFolder, DownloadCache.getDir());
		TaskableExecutor libExecutor = new TaskableExecutor(2, new Runnable(){

			@Override
			public void run() {
				for (String entry : libExtract){
					Archive.extractZip(instancePath.resolve("lib").resolve(entry).toFile(), instancePath.resolve("lib").resolve("natives").toFile(), false);
				}				
			}});
		libraryQueue.processQueue(libExecutor);
		final File branding = new File(tmpFolder, "fmlbranding.properties");
		try {
			branding.createNewFile();
			Properties propBrand = new Properties();
			propBrand.setProperty("fmlbranding", "MCUpdater: " + server.getName() + " (rev " + server.getRevision() + ")");
			propBrand.store(new FileOutputStream(branding), "MCUpdater ServerPack branding");
		} catch (IOException e1) {
			apiLogger.log(Level.SEVERE, "I/O Error", e1);
		}
		final boolean doJarUpdate = updateJar;
		TaskableExecutor jarExecutor = new TaskableExecutor(2, new Runnable() {
			
			@Override
			public void run() {
				if (!doJarUpdate) {
					try {
						Archive.updateArchive(productionJar.toFile(), new File[]{ branding });
					} catch (IOException e1) {
						apiLogger.log(Level.SEVERE, "I/O Error", e1);
					}
				} else {
					for (Map.Entry<String,Boolean> entry : keepMeta.entrySet()) {
						File entryFile = new File(tmpFolder,entry.getKey());
						Archive.extractZip(entryFile, tmpFolder, entry.getValue());
						entryFile.delete();
					}
					try {
						buildJar.createNewFile();
					} catch (IOException e) {
						apiLogger.log(Level.SEVERE, "I/O Error", e);
					}
					boolean doManifest = true;
					List<File> buildList = recurseFolder(tmpFolder,true);
					for (File entry : new ArrayList<>(buildList)) {
						if (entry.getPath().contains("META-INF")) {
							doManifest = false;
						}
					}
					parent.log("Packaging updated jar...");
					try {
						Archive.createJar(buildJar, buildList, tmpFolder.getPath() + sep, doManifest);
					} catch (IOException e1) {
						parent.log("Failed to create jar!");
						apiLogger.log(Level.SEVERE, "I/O Error", e1);
					}
					//Archive.patchJar(jar, buildJar, new ArrayList<File>(Arrays.asList(tmpFolder.listFiles())));
					//copyFile(buildJar, new File(MCFolder + sep + "bin" + sep + "minecraft.jar"));
					try {
						Files.createDirectories(productionJar.getParent());
						Files.copy(buildJar.toPath(), productionJar, StandardCopyOption.REPLACE_EXISTING);
					} catch (IOException e) {
						apiLogger.log(Level.SEVERE, "Failed to copy new jar to instance!", e);
					}
				}
				List<File> tempFiles = recurseFolder(tmpFolder,true);
				ListIterator<File> li = tempFiles.listIterator(tempFiles.size());
				while(li.hasPrevious()) { 
					File entry = li.previous();
					entry.delete();
				}
				if (server.isGenerateList()) { writeMCServerFile(server.getName(), server.getAddress(), server.getServerId()); }
				instData.setMCVersion(server.getVersion());
				instData.setRevision(server.getRevision());
				String jsonOut = gson.toJson(instData);
				try {
					BufferedWriter writer = Files.newBufferedWriter(getInstanceRoot().resolve(server.getServerId()).resolve("instance.json"), StandardCharsets.UTF_8);
					writer.append(jsonOut);
					writer.close();
				} catch (IOException e) {
					apiLogger.log(Level.SEVERE, "I/O error", e);
				}		
			}
		});
		jarQueue.processQueue(jarExecutor);
		TaskableExecutor genExecutor = new TaskableExecutor(12, new Runnable(){

			@Override
			public void run() {
				for (Map.Entry<String,Boolean> entry : modExtract.entrySet()) {
					if (entry.getValue()) {
						Archive.extractZip(instancePath.resolve(entry.getKey()).toFile(), instancePath.toFile());
					} else {
						Archive.extractZip(instancePath.resolve(entry.getKey()).toFile(), instancePath.resolve("mods").toFile());
					}
					instancePath.resolve(entry.getKey()).toFile().delete();
				}
			}
			
		});
		generalQueue.processQueue(genExecutor);
		TaskableExecutor assetsExecutor = new TaskableExecutor(8, new Runnable(){
			
			@Override
			public void run() {
				//check virtual
				Gson gson = new Gson();
				String indexName = version.getAssets();
				if (indexName == null) {
					indexName = "legacy";
				}
				File indexesPath = archiveFolder.resolve("assets").resolve("indexes").toFile();
				File indexFile = new File(indexesPath, indexName + ".json");
				String json;
				try {
					json = FileUtils.readFileToString(indexFile);
					AssetIndex index = gson.fromJson(json, AssetIndex.class);
					parent.log("Assets virtual: " + index.isVirtual());
					if (index.isVirtual()) {
						//Test symlink support
						boolean doLinks = true;
						try {
							java.nio.file.Files.createSymbolicLink(archiveFolder.resolve("linktest"), archiveFolder.resolve("MCUpdater.log.0"));
							archiveFolder.resolve("linktest").toFile().delete();
						} catch (Exception e) {
							doLinks = false;
						}
						Path assetsPath = archiveFolder.resolve("assets");
						Path virtualPath = assetsPath.resolve("virtual");
						for (Map.Entry<String, Asset> entry : index.getObjects().entrySet()) {
							Path target = virtualPath.resolve(entry.getKey());
							Path original = assetsPath.resolve("objects").resolve(entry.getValue().getHash().substring(0,2)).resolve(entry.getValue().getHash());
							
							if (!Files.exists(target)) {
								Files.createDirectories(target.getParent());
								if (doLinks) {
									Files.createSymbolicLink(target, original);
								} else {
									Files.copy(original, target);
								}
							}
						}
					}
				} catch (IOException e) {
					parent.baseLogger.log(Level.SEVERE, "Assets exception! " + e.getMessage());
				}

			}
			
		});
		assetsQueue.processQueue(assetsExecutor);
		if( errorCount > 0 ) {
			parent.baseLogger.severe("Errors were detected with this update, please verify your files. There may be a problem with the serverpack configuration or one of your download sites.");
			return false;
		}
		//copyFile(jar, buildJar);
		return true;
	}
	
	private String cleanForFile(String id) {
		return id.replaceAll("[^a-zA-Z_0-9\\-.]", "_");
	}

	public void writeMCServerFile(String name, String ip, String instance) {
		byte[] header = new byte[]{
				0x0A,0x00,0x00,0x09,0x00,0x07,0x73,0x65,0x72,0x76,0x65,0x72,0x73,0x0A,
				0x00,0x00,0x00,0x01,0x01,0x00,0x0B,0x68,0x69,0x64,0x65,0x41,0x64,0x64,
				0x72,0x65,0x73,0x73,0x01,0x08,0x00,0x04,0x6E,0x61,0x6D,0x65,0x00,
				(byte) (name.length() + 12), (byte) 0xC2,(byte) 0xA7,0x41,0x5B,0x4D,0x43,0x55,0x5D,0x20,(byte) 0xC2,(byte) 0xA7,0x46
				};
		byte[] nameBytes = name.getBytes();
		byte[] ipBytes = ip.getBytes();
		byte[] middle = new byte[]{0x08,0x00,0x02,0x69,0x70,0x00,(byte) ip.length()};
		byte[] end = new byte[]{0x00,0x00};
		int size = header.length + nameBytes.length + middle.length + ipBytes.length + end.length;
		byte[] full = new byte[size];
		int pos = 0;
		System.arraycopy(header, 0, full, pos, header.length);
		pos += header.length;
		System.arraycopy(nameBytes, 0, full, pos, nameBytes.length);
		pos += nameBytes.length;
		System.arraycopy(middle, 0, full, pos, middle.length);
		pos += middle.length;
		System.arraycopy(ipBytes, 0, full, pos, ipBytes.length);
		pos += ipBytes.length;
		System.arraycopy(end, 0, full, pos, end.length);
		File serverFile = instanceRoot.resolve(instance).resolve("servers.dat").toFile();
		try {
			serverFile.createNewFile();
			FileOutputStream fos = new FileOutputStream(serverFile);
			fos.write(full,0,full.length);
			fos.close();
		} catch (IOException e) {
			apiLogger.log(Level.SEVERE, "I/O Error", e);
		}
		
	}

	public static void openLink(URI uri) {
		try {
			Object o = Class.forName("java.awt.Desktop").getMethod("getDesktop", new Class[0]).invoke(null);
			o.getClass().getMethod("browse", new Class[] { URI.class }).invoke(o, uri);
		} catch (Throwable e) {
			_log("Failed to open link " + uri.toString());
		}
	}
	
	private static void _log(String msg) {
		apiLogger.info(msg);
	}
	private static void _debug(String msg) {
		apiLogger.fine(msg);
	}

	/*
	public boolean checkVersionCache(String version, ModSide side) {
		File requestedJar;
		switch (side) {
		case CLIENT:
			requestedJar = archiveFolder.resolve("mc-" + version + ".jar").toFile();
			File newestJar = archiveFolder.resolve("mc-" + newestMC + ".jar").toFile();
			if (requestedJar.exists()) return true;
			if (newestJar.exists()) {
				doPatch(requestedJar, newestJar, version);
				return true;
			} else {
				if (this.getParent().requestLogin()) {
					try {
						parent.setStatus("Downloading Minecraft");
						apiLogger.info("Downloading Minecraft (" + newestMC + ")");
						FileUtils.copyURLToFile(new URL("http://assets.minecraft.net/" + newestMC.replace(".","_") + "/minecraft.jar"), newestJar);
					} catch (MalformedURLException e) {
						apiLogger.log(Level.SEVERE, "Bad URL", e);
						return false;
					} catch (IOException e) {
						apiLogger.log(Level.SEVERE, "I/O Error", e);
						return false;
					}
					if (!requestedJar.toString().equals(newestJar.toString())) {
						doPatch(requestedJar, newestJar, version);
					}
					return true;
				} else {
					return false;
				}
			}
		case SERVER:
			requestedJar = archiveFolder.resolve("mc-server-" + version + ".jar").toFile();
			if (requestedJar.exists()) return true;
			try {
				apiLogger.info("Downloading server jar (" + version + ")");
				FileUtils.copyURLToFile(new URL("http://assets.minecraft.net/" + version.replace(".","_") + "/minecraft_server.jar"), requestedJar);
			} catch (MalformedURLException e) {
				apiLogger.log(Level.SEVERE, "Bad URL", e);
				return false;
			} catch (IOException e) {
				apiLogger.log(Level.SEVERE, "I/O Error", e);
				return false;
			}
			return true;
		default:
			break;
		}
		return false;
	}
	*/
	
	/*
	private void doPatch(File requestedJar, File newestJar, String version) {
		try {
			URL patchURL;
			File patchFile = archiveFolder.resolve("temp.patch").toFile();
			try {
				patchURL = new URL("http://files.mcupdater.com/mcu_patches/" + newestMC.replace(".", "") + "to" + version.replace(".","") + ".patch");
				patchURL.openConnection().connect();
			} catch (IOException ioe) {
				patchURL = new URL("https://dl.dropboxusercontent.com/u/75552727/mcu_patches/" + newestMC.replace(".", "") + "to" + version.replace(".","") + ".patch");
			}
			_debug(patchURL.toString());
			parent.setStatus("Downloading downgrade patch");
			apiLogger.info("Downloading downgrade patch (" + newestMC + " -> " + version + ")");
			FileUtils.copyURLToFile(patchURL, patchFile, 2000, 5000);
			parent.setStatus("Applying downgrade patch");
			apiLogger.info("Applying downgrade patch");
			Transmogrify.applyPatch(new Path(newestJar), new Path(requestedJar), new Path(patchFile));
			patchFile.delete();
		} catch (Exception e) {
			apiLogger.log(Level.SEVERE, "General Error", e);
		}
	}
	*/

	private Cipher getCipher(int mode, String password) throws Exception {
		Random random = new Random(92845025L);
		byte[] salt = new byte[8];
		random.nextBytes(salt);
		PBEParameterSpec pbeParamSpec = new PBEParameterSpec(salt, 5);

		SecretKey pbeKey = SecretKeyFactory.getInstance("PBEWithMD5AndDES").generateSecret(new PBEKeySpec(password.toCharArray()));
		Cipher cipher = Cipher.getInstance("PBEWithMD5AndDES");
		cipher.init(mode, pbeKey, pbeParamSpec);
		return cipher;
	}

	public String encrypt(String password) {
		try {
			Cipher cipher = getCipher(Cipher.ENCRYPT_MODE, "MCUpdater");
			byte[] utf8 = password.getBytes("UTF8");
			byte[] enc = cipher.doFinal(utf8);

			return Base64.encodeBase64String(enc);
		} catch (Exception e) {
			apiLogger.log(Level.SEVERE, "General error", e);
		}
		return null;
	}

	public String decrypt(String property) {
		try {
			Cipher cipher = getCipher(Cipher.DECRYPT_MODE, "MCUpdater");
			byte[] dec = Base64.decodeBase64(property);
			byte[] utf8 = cipher.doFinal(dec);

			return new String(utf8, "UTF8");
		} catch (Exception e) {
			apiLogger.log(Level.SEVERE, "General error", e);
		}
		return null;
	}

	public void setTimeout(int timeout) {
		this.timeoutLength = timeout;
	}
	
	public int getTimeout() {
		return this.timeoutLength;
	}

	public static String calculateGroupHash(Set<String> digests) {
		BigInteger hash = BigInteger.valueOf(0);
		for (String entry : digests) {
			try {
				BigInteger digest = new BigInteger(Hex.decodeHex(entry.toCharArray()));
				hash = hash.xor(digest);
			} catch (DecoderException e) {
				//e.printStackTrace();
				System.out.println("Entry '" + entry + "' is not a valid hexadecimal number");
			}
		}
		return Hex.encodeHexString(hash.toByteArray());
	}
}

/* 0
//for (PrioritizedURL pUrl : entry.getUrls()) {
//	_debug("Mod @ "+pUrl.getUrl());
//	URL modURL = new URL(pUrl.getUrl());
	//String modFilename = modURL.getFile().substring(modURL.getFile().lastIndexOf('/'));
	File modPath;
	if(entry.getInJar()) {
		if (updateJar) {
			//modPath = new File(tmpFolder.getPath() + sep + loadOrder + ".zip");
			//loadOrder++;
			//_log(modPath.getPath());
			ModDownload jarMod;
			try {
				jarMod = new ModDownload(modURL, File.createTempFile(entry.getId(), ".jar"), entry.getMD5());
				if( jarMod.cacheHit ) {
					parent.log("  Adding to jar (cached).");
				} else {
					parent.log("  Adding to jar (downloaded).");
				}
				_debug(jarMod.url + " -> " + jarMod.getDestFile().getPath());
				//FileUtils.copyURLToFile(modURL, modPath);
				Archive.extractZip(jarMod.getDestFile(), tmpFolder, entry.getKeepMeta());
				jarMod.getDestFile().delete();
				instData.setProperty("mod:" + entry.getId(), entry.getMD5());
				jarModCount++;
			} catch (Exception e) {
				++errorCount;
				apiLogger.log(Level.SEVERE, "General Error", e);						}
		} else {
			parent.log("Skipping jar mod: " + entry.getName());
		}
	} else if (entry.getExtract()) {
		//modPath = new File(tmpFolder.getPath() + sep + modFilename);
		//modPath.getParentFile().mkdirs();
		//_log(modPath.getPath());
		ModDownload extractMod;
		try {
			extractMod = new ModDownload(modURL, File.createTempFile(entry.getId(), ".jar") , entry.getMD5());
			if( extractMod.cacheHit ) {
				parent.log("  Extracting to filesystem (cached).");
			} else {
				parent.log("  Extracting to filesystem (downloaded).");
			}
			_debug(extractMod.url + " -> " + extractMod.getDestFile().getPath());
			//FileUtils.copyURLToFile(modURL, modPath);
			Path destPath = instancePath;
			if(!entry.getInRoot()) destPath = instancePath.resolve("mods");
			Archive.extractZip(extractMod.getDestFile(), destPath.toFile());
			extractMod.getDestFile().delete();
		} catch (Exception e) {
			++errorCount;
			apiLogger.log(Level.SEVERE, "General Error", e);
		}
	} else if (entry.getCoreMod()) {
		modPath = instancePath.resolve("coremods").resolve(cleanForFile(entry.getId()) + ".jar").toFile();
		modPath.getParentFile().mkdirs();
		try {
			ModDownload normalMod = new ModDownload(modURL, modPath, entry.getMD5());
			if( normalMod.cacheHit ) {
				parent.log("  Installing in /coremods (cached).");
			} else {
				parent.log("  Installing in /coremods (downloaded).");
			}
			_debug(normalMod.url + " -> " + normalMod.getDestFile().getPath());
		} catch (Exception e) {
			++errorCount;
			apiLogger.log(Level.SEVERE, "General Error", e);
		}					
	} else {
		if (entry.getPath().equals("")){
			modPath = instancePath.resolve("mods").resolve(cleanForFile(entry.getId()) + ".jar").toFile();
		} else {
			modPath = instancePath.resolve(entry.getPath()).toFile();
		}
		modPath.getParentFile().mkdirs();
		//_log("~~~ " + modPath.getPath());
		try {
			ModDownload normalMod = new ModDownload(modURL, modPath, entry.getMD5());
			if( normalMod.cacheHit ) {
				parent.log("  Installing in /mods (cached).");
			} else {
				parent.log("  Installing in /mods (downloaded).");
			}
			_debug(normalMod.url + " -> " + normalMod.getDestFile().getPath());
		} catch (Exception e) {
			++errorCount;
			apiLogger.log(Level.SEVERE, "General Error", e);
		}
		//FileUtils.copyURLToFile(modURL, modPath);
	}
}*/

/* 1
					final String MD5 = cfEntry.getMD5(); 
_debug(cfEntry.getUrl());
URL configURL = new URL(cfEntry.getUrl());
final File confFile = instancePath.resolve(cfEntry.getPath()).toFile();
confFile.getParentFile().mkdirs();
//					if( MD5 != null ) {
//						final File cacheFile = DownloadCache.getFile(MD5);
//						if( cacheFile.exists() ) {
//							parent.log("  Found config for "+cfEntry.getPath()+" (cached)");
//							FileUtils.copyFile(cacheFile, confFile);
//							continue;
//						}
//					}
//_debug(confFile.getPath());
if (cfEntry.isNoOverwrite() && confFile.exists()) {
	parent.log("  Config for "+cfEntry.getPath()+" skipped - NoOverwrite is true");
} else {
	//parent.log("  Found config for "+cfEntry.getPath()+", downloading...");
	try {
		ModDownload configDL = new ModDownload(configURL, confFile, MD5);
		if( configDL.cacheHit ) {
			parent.log("  Found config for "+cfEntry.getPath()+" (cached).");
		} else {
			parent.log("  Found config for "+cfEntry.getPath()+" (downloaded).");
		}
		String strPath = configDL.getDestFile() == null ? "???" : configDL.getDestFile().getPath();
		_debug(configDL.url + " -> " + strPath);
	} catch (Exception e) {
		++errorCount;
		apiLogger.log(Level.SEVERE, "General Error", e);
	}
	//FileUtils.copyURLToFile(configURL, confFile);
}
*/
