package com.starlocalrag.tokenizers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Native Library Loader
 * Responsible for loading the appropriate native library for the current platform
 */
public class NativeLibraryLoader {
    private static final Logger LOGGER = Logger.getLogger(NativeLibraryLoader.class.getName());
    private static boolean loaded = false;
    
    /**
     * Load the native library
     * @throws IOException if loading fails
     */
    public static synchronized void load() throws IOException {

        
        if (loaded) {

            LOGGER.fine("Native library already loaded, skipping");
            return;
        }
        
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        

        LOGGER.fine("Operating system: " + os + ", architecture: " + arch);
        
        // Check if running in Android environment
        boolean isAndroid = System.getProperty("java.vm.vendor", "").contains("Android") || 
                          System.getProperty("java.vendor", "").contains("Android") ||
                          System.getProperty("java.vm.name", "").contains("Android");
        

        LOGGER.fine("Is Android environment: " + isAndroid);
        
        String libName;
        String resourcePath;
        String androidArch = null;
        
        if (isAndroid) {
            // Android platform uses specific library name and path
            libName = "libtokenizers_jni.so";
            
            // Select the correct library path based on CPU architecture
            if (arch.contains("arm64") || arch.contains("aarch64")) {
                androidArch = "arm64-v8a";
            } else if (arch.contains("arm")) {
                androidArch = "armeabi-v7a";
            } else if (arch.contains("x86_64")) {
                androidArch = "x86_64";
            } else if (arch.contains("x86")) {
                androidArch = "x86";
            } else {
                String errorMsg = "Unsupported Android CPU architecture: " + arch;
                LOGGER.severe(errorMsg);
                throw new UnsupportedOperationException(errorMsg);
            }
            
            
            LOGGER.fine("Detected Android architecture: " + androidArch);
            resourcePath = "/native/android/" + androidArch + "/" + libName;

            LOGGER.fine("Resource path: " + resourcePath);
        } else if (os.contains("win")) {
            libName = "tokenizers_jni.dll";
            resourcePath = "/native/windows/" + arch + "/" + libName;
        } else if (os.contains("linux")) {
            libName = "libtokenizers_jni.so";
            resourcePath = "/native/linux/" + arch + "/" + libName;
        } else if (os.contains("mac")) {
            libName = "libtokenizers_jni.dylib";
            resourcePath = "/native/macos/" + arch + "/" + libName;
        } else {
            throw new UnsupportedOperationException("Unsupported operating system: " + os);
        }
        
        try {
            // On Android platform, try to load the library directly first
            if (isAndroid) {
                try {
                    // Try to load the library directly (system will automatically look in the app's jniLibs directory)
        
                    LOGGER.fine("Attempting to load library directly: tokenizers_jni");
                    
                    System.loadLibrary("tokenizers_jni");
                    
    
                    LOGGER.fine("Successfully loaded library directly");
                    loaded = true;
                    return;
                } catch (UnsatisfiedLinkError e) {
                    // If direct loading fails, log the error and continue with other methods
                    System.err.println("[DEBUG] NativeLibraryLoader: 直接加载库失败: " + e.getMessage());
                    e.printStackTrace();
                    LOGGER.warning("Failed to load library directly: " + e.getMessage());
                    
                    // Try to list possible library paths for debugging
                    try {
                        File appDir = new File("/data/app");
                        if (appDir.exists() && appDir.isDirectory()) {
                            LOGGER.fine("App directory exists: " + appDir.getAbsolutePath());
                            
                            // List all app directories
                            File[] appDirs = appDir.listFiles();
                            if (appDirs != null) {
                                for (File dir : appDirs) {
                                    if (dir.isDirectory() && dir.getName().contains("com.")) {
                                        LOGGER.fine("Found app directory: " + dir.getAbsolutePath());
                                        
                                        // Check lib directory
                                        File libDir = new File(dir, "lib");
                                        if (libDir.exists() && libDir.isDirectory()) {
                                            LOGGER.fine("Found lib directory: " + libDir.getAbsolutePath());
                                            
                                            // Check architecture directories
                                            File[] archDirs = libDir.listFiles();
                                            if (archDirs != null) {
                                                for (File archDir : archDirs) {
                                                    LOGGER.fine("Architecture directory: " + archDir.getName());
                                                    
                                                    // Check library files
                                                    File[] libs = archDir.listFiles();
                                                    if (libs != null) {
                                                        for (File lib : libs) {
                                                            LOGGER.fine("Library file: " + lib.getName());
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        LOGGER.warning("Error listing app directories: " + ex.getMessage());
                    }
                }
            }
            
            // If not Android platform or direct loading fails, try to load from resources
            LOGGER.fine("Attempting to load library from resources");
            // Create temporary directory using API level 24 compatible method
            File tempDirFile = new File(System.getProperty("java.io.tmpdir"), "tokenizers-jni-" + System.currentTimeMillis());
            if (!tempDirFile.mkdirs()) {
                throw new IOException("Failed to create temporary directory: " + tempDirFile.getAbsolutePath());
            }
            File tempLibFile = new File(tempDirFile, libName);
            LOGGER.fine("Temporary library path: " + tempLibFile.getAbsolutePath());
            
            try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    LOGGER.warning("Library not found in resources: " + resourcePath);
                    
                    // List possible resource paths for debugging
                    String[] paths = {
                        "/native",
                        "/native/android",
                        "/native/android/" + androidArch,
                        "/jni",
                        "/lib"
                    };
                    
                    for (String path : paths) {
                        try (InputStream testIn = NativeLibraryLoader.class.getResourceAsStream(path)) {
                            LOGGER.fine("Checking resource path: " + path + " - " + (testIn != null ? "exists" : "does not exist"));
                        } catch (Exception e) {
                            LOGGER.warning("Error checking resource path: " + path + " - " + e.getMessage());
                        }
                    }
                } else {
                    LOGGER.fine("Library found in resources, copying to temporary directory");
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(tempLibFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                }
            }
            
            // If resource doesn't exist, try to load from local file system
            File localLib = new File("libs/tokenizers-jni/src/main/resources" + resourcePath);
            LOGGER.fine("Attempting to load from local file system: " + localLib.getAbsolutePath());
            if (localLib.exists()) {
                LOGGER.fine("Local library file exists, copying to temporary directory");
                try (java.io.FileInputStream fis = new java.io.FileInputStream(localLib);
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(tempLibFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
            } else {
                // Try to find in other possible locations
                String[] possiblePaths = {
                    "libs/tokenizers-jni/build/libs/tokenizers-jni/native/android/" + androidArch + "/" + libName,
                    "src/main/jniLibs/" + androidArch + "/" + libName,
                    "jniLibs/" + androidArch + "/" + libName
                };
                
                boolean found = false;
                for (String path : possiblePaths) {
                    File possibleLib = new File(path);
                    LOGGER.fine("Checking possible library path: " + possibleLib.getAbsolutePath() + " - " + (possibleLib.exists() ? "exists" : "does not exist"));
                    if (possibleLib.exists()) {
                        LOGGER.fine("Found library file, copying to temporary directory");
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(possibleLib);
                             java.io.FileOutputStream fos = new java.io.FileOutputStream(tempLibFile)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = fis.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                            }
                        }
                        found = true;
                        break;
                    }
                }
                
                if (!found) {
                    String errorMsg = "Native library not found: " + resourcePath;
                    LOGGER.severe(errorMsg);
                    throw new IOException(errorMsg);
                }
            }
            
            String libPath = tempLibFile.getAbsolutePath();
            LOGGER.fine("Loading temporary library: " + libPath);
            System.load(libPath);
            LOGGER.fine("Library loaded successfully");
            tempDirFile.deleteOnExit();
            tempLibFile.deleteOnExit();
            
            loaded = true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load native library: " + e.getMessage(), e);
            throw new IOException("Failed to load native library", e);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.log(Level.SEVERE, "Failed to link native library: " + e.getMessage(), e);
            throw new IOException("Failed to link native library: " + e.getMessage(), e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unknown error loading native library: " + e.getMessage(), e);
            throw new IOException("Unknown error loading native library", e);
        }
    }
}
