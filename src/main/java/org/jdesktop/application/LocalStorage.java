/*
 * Copyright (C) 2006 Sun Microsystems, Inc. All rights reserved. Use is
 * subject to license terms.
 */
package org.jdesktop.application;

import org.jdesktop.application.utils.AppHelper;
import org.jdesktop.application.utils.PlatformType;

import java.awt.*;
import java.beans.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.DefaultListModel;

import static org.jdesktop.application.Application.KEY_APPLICATION_VENDOR_ID;

/**
 * Access to per application, per user, local file storage.
 *
 * @author Hans Muller (Hans.Muller@Sun.COM)
 * @see ApplicationContext#getLocalStorage
 * @see SessionStorage
 */
public class LocalStorage extends AbstractBean {

    private static final Logger logger = Logger.getLogger(LocalStorage.class.getName());
    private final ApplicationContext context;
    private long storageLimit = -1L;
    private LocalIO localIO = null;
    private final File unspecifiedFile = new File("unspecified");
    private File directory = unspecifiedFile;
    private Map<Class<?>, PersistenceDelegate> persistentDelegatesMap;

    static {
        Map<Class<?>, PersistenceDelegate> fixInternals = new HashMap<Class<?>, PersistenceDelegate>();

        fixInternals.put(DefaultListModel.class, new DefaultListModelPD());
        fixInternals.put(File.class, new DefaultPersistenceDelegate(new String[]{"path"}));
        fixInternals.put(URL.class, new PrimitivePersistenceDelegate());

        // JDK bug ID 4741757 was fixed with the release of JDK 1.6 (It is listed in the JDK 6 Adoption Guide).
        // As a consequence, the RectanglePD is not needed since its release.
        // Removing it resolves BSAF-107.
        if (System.getProperty("java.version").compareTo("1.6") < 0) {
            fixInternals.put(Rectangle.class, new RectanglePD());
        }

        for (Map.Entry<Class<?>, PersistenceDelegate> fixInternal : fixInternals.entrySet()) {
            try {
                Introspector.getBeanInfo(fixInternal.getKey()).getBeanDescriptor().setValue("persistenceDelegate", fixInternal.getValue());
            } catch (IntrospectionException ex) {
                throw new ExceptionInInitializerError("Unable to load " + fixInternal.getKey() + " fix.");
            }
        }
    }

    protected LocalStorage(ApplicationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("null context");
        }
        this.context = context;
    }

    // FIXME - documentation
    protected final ApplicationContext getContext() {
        return context;
    }

    private void checkFileName(String fileName) {
        if (fileName == null) {
            throw new IllegalArgumentException("null fileName");
        }
    }

    /**
     * Opens an input stream to read from the entry
     * specified by the {@code name} parameter.
     * If the named entry cannot be opened for reading
     * then a {@code IOException} is thrown.
     *
     * @param fileName the storage-dependent name
     * @return an {@code InputStream} object
     * @throws IOException if the specified name is invalid,
     *                     or an input stream cannot be opened
     */
    public InputStream openInputFile(String fileName) throws IOException {
        checkFileName(fileName);
        return getLocalIO().openInputFile(fileName);
    }

    /**
     * Opens an output stream to write to the entry
     * specified by the {@code name} parameter.
     * If the named entry cannot be opened for writing
     * then a {@code IOException} is thrown.
     * If the named entry does not exist it can be created.
     * The entry will be recreated if already exists.
     *
     * @param fileName the storage-dependent name
     * @return an {@code OutputStream} object
     * @throws IOException if the specified name is invalid,
     *                     or an output stream cannot be opened
     */
    public OutputStream openOutputFile(final String fileName) throws IOException {
        return openOutputFile(fileName, false);
    }

    /**
     * Opens an output stream to write to the entry
     * specified by the {@code name} parameter.
     * If the named entry cannot be opened for writing
     * then a {@code IOException} is thrown.
     * If the named entry does not exist it can be created.
     * You can decide whether data will be appended via append parameter.
     *
     * @param fileName the storage-dependent name
     * @param append   if <code>true</code>, then bytes will be written
     *                 to the end of the output entry rather than the beginning
     * @return an {@code OutputStream} object
     * @throws IOException if the specified name is invalid,
     *                     or an output stream cannot be opened
     */
    public OutputStream openOutputFile(String fileName, boolean append) throws IOException {
        checkFileName(fileName);
        return getLocalIO().openOutputFile(fileName, append);
    }

    /**
     * Deletes the entry specified by the {@code name} parameter.
     *
     * @param fileName the storage-dependent name
     * @throws IOException if the specified name is invalid,
     *                     or an internal entry cannot be deleted
     */
    public boolean deleteFile(String fileName) throws IOException {
        checkFileName(fileName);
        return getLocalIO().deleteFile(fileName);
    }

    /* If an exception occurs in the XMLEncoder/Decoder, we want
     * to throw an IOException.  The exceptionThrow listener method
     * doesn't throw a checked exception so we just set a flag
     * here and check it when the encode/decode operation finishes
     */
    private static class AbortExceptionListener implements ExceptionListener {

        public Exception exception = null;

        @Override
        public void exceptionThrown(Exception e) {
            if (exception == null) {
                exception = e;
            }
        }
    }


    /**
     * Saves the {@code bean} to the local storage
     *
     * @param bean     the object ot be saved
     * @param fileName the targen file name
     * @throws IOException
     */
    public void save(Object bean, final String fileName) throws IOException {
        AbortExceptionListener el = new AbortExceptionListener();
        XMLEncoder e = null;
        /* Buffer the XMLEncoder's output so that decoding errors don't
         * cause us to trash the current version of the specified file.
         */
        ByteArrayOutputStream bst = new ByteArrayOutputStream();
        try {
            e = new XMLEncoder(bst);
            //necessary for JDK 7
            //we need to set it up every time XMLEncoder is being instantiated
            if (persistentDelegatesMap != null) {
                for (Map.Entry<Class<?>, PersistenceDelegate> entry : persistentDelegatesMap.entrySet()) {
                    e.setPersistenceDelegate(entry.getKey(), entry.getValue());
                }
            }
            e.setExceptionListener(el);
            e.writeObject(bean);
        } finally {
            if (e != null) {
                e.close();
            }
        }
        if (el.exception != null) {
            throw new IOException("save failed \"" + fileName + "\"", el.exception);
        }
        OutputStream ost = null;
        try {
            ost = openOutputFile(fileName);
            ost.write(bst.toByteArray());
        } finally {
            if (ost != null) {
                ost.close();
            }
        }
    }

    /**
     * Loads the bean from the local storage
     *
     * @param fileName name of the file to be read from
     * @return loaded object
     * @throws IOException
     */
    public Object load(String fileName) throws IOException {
        InputStream ist;
        try {
            ist = openInputFile(fileName);
        } catch (IOException e) {
            return null;
        }
        AbortExceptionListener el = new AbortExceptionListener();
        XMLDecoder d = null;
        try {
            d = new XMLDecoder(ist);
            d.setExceptionListener(el);
            Object bean = d.readObject();
            if (el.exception != null) {
                throw new IOException("load failed \"" + fileName + "\"", el.exception);
            }
            return bean;
        } finally {
            if (d != null) {
                d.close();
            }
        }
    }

//    private void closeStream(Closeable st, String fileName) throws IOException {
//        if (st != null) {
//            try {
//                st.close();
//            } catch (java.io.IOException e) {
//                throw new LSException("close failed \"" + fileName + "\"", e);
//            }
//        }
//    }

    /**
     * Gets the limit of the local storage
     *
     * @return the limit of the local storage
     */
    public long getStorageLimit() {
        return storageLimit;
    }

    /**
     * Sets the limit of the local storage
     *
     * @param storageLimit the limit of the local storage
     */
    public void setStorageLimit(long storageLimit) {
        if (storageLimit < -1L) {
            throw new IllegalArgumentException("invalid storageLimit");
        }
        long oldValue = this.storageLimit;
        this.storageLimit = storageLimit;
        firePropertyChange("storageLimit", oldValue, this.storageLimit);
    }

    private String getId(String key, String def) {
        ResourceMap appResourceMap = getContext().getResourceMap();
        String id = appResourceMap.getString(key);
        if (id == null) {
            logger.log(Level.WARNING, "unspecified resource " + key + " using " + def);
            id = def;
        } else if (id.trim().length() == 0) {
            logger.log(Level.WARNING, "empty resource " + key + " using " + def);
            id = def;
        }
        return id;
    }

    private String getApplicationId() {
        return getId("Application.id", getContext().getApplicationClass().getSimpleName());
    }

    private String getVendorId() {
        return getId(KEY_APPLICATION_VENDOR_ID, "UnknownApplicationVendor");
    }


    /**
     * Method sets persistent delegates for XML Encoder which is being used for serialization of the bean.<br />
     *
     * @param persistentDelegatesMap map with persistent delegates
     * @see java.beans.Encoder#setPersistenceDelegate(java.lang.Class, java.beans.PersistenceDelegate)
     * @since 1.9.3
     */
    public void setPersistentDelegates(Map<Class<?>, PersistenceDelegate> persistentDelegatesMap) {
        this.persistentDelegatesMap = persistentDelegatesMap;
    }


    /**
     * Method returns persistent delegates for XML Encoder, which is being used for serialization of the bean.<br />
     * See http://kenai.com/jira/browse/BSAF-61 for more details.
     *
     * @see java.beans.Encoder#getPersistenceDelegate
     * @since 1.9.3
     */
    public Map<Class<?>, PersistenceDelegate> getPersistentDelegates() {
        if (persistentDelegatesMap == null) {
            persistentDelegatesMap = new HashMap<Class<?>, PersistenceDelegate>();
        }

        return persistentDelegatesMap;
    }

    /**
     * Returns the directory where the local storage is located
     *
     * @return the directory where the local storage is located
     */
    public File getDirectory() {
        if (directory == unspecifiedFile) {
            directory = null;
            String userHome = null;
            try {
                userHome = System.getProperty("user.home");
            } catch (SecurityException ignore) {
            }
            if (userHome != null) {
                final String applicationId = getApplicationId();
                final PlatformType osId = AppHelper.getPlatform();
                if (osId == PlatformType.WINDOWS) {
                    File appDataDir = null;
                    try {
                        String appDataEV = System.getenv("APPDATA");
                        if ((appDataEV != null) && (appDataEV.length() > 0)) {
                            appDataDir = new File(appDataEV);
                        }
                    } catch (SecurityException ignore) {
                    }
                    String vendorId = getVendorId();
                    if ((appDataDir != null) && appDataDir.isDirectory()) {
                        // ${APPDATA}\{vendorId}\${applicationId}
                        String path = vendorId + "\\" + applicationId + "\\";
                        directory = new File(appDataDir, path);
                    } else {
                        // ${userHome}\Application Data\${vendorId}\${applicationId}
                        String path = "Application Data\\" + vendorId + "\\" + applicationId + "\\";
                        directory = new File(userHome, path);
                    }
                } else if (osId == PlatformType.OS_X) {
                    // ${userHome}/Library/Application Support/${applicationId}
                    String path = "Library/Application Support/" + applicationId + "/";
                    directory = new File(userHome, path);
                } else {
                    // ${userHome}/.${applicationId}/
                    String path = "." + applicationId + "/";
                    directory = new File(userHome, path);
                }
            }
        }
        return directory;
    }

    /**
     * Sets the location of the local storage
     *
     * @param directory the location of the local storage
     */
    public void setDirectory(File directory) {
        File oldValue = this.directory;
        this.directory = directory;
        firePropertyChange("directory", oldValue, this.directory);
    }

    /* There are some (old) Java classes that aren't proper beans.  Rectangle
     * is one of these.  When running within the secure sandbox, writing a
     * Rectangle with XMLEncoder causes a security exception because
     * DefaultPersistenceDelegate calls Field.setAccessible(true) to gain
     * access to private fields.  This is a workaround for that problem.
     * A bug has been filed, see JDK bug ID 4741757.
     *
     * The JDK bug was fixed in version 1.6 b76.
     */
    private static class RectanglePD extends DefaultPersistenceDelegate {

        public RectanglePD() {
            super(new String[]{"x", "y", "width", "height"});
        }

        @Override
        protected Expression instantiate(Object oldInstance, Encoder out) {
            Rectangle oldR = (Rectangle) oldInstance;
            Object[] constructorArgs = new Object[]{
                    oldR.x, oldR.y, oldR.width, oldR.height
            };
            return new Expression(oldInstance, oldInstance.getClass(), "new", constructorArgs);
        }
    }

    /* The JVM's persistence delegate in Java 1.6 and 1.7 is invalid in that the persisted model is a list of null values.
     * The problem is that the author incorrectly assumed that the newInstance would be created empty when, in
     * fact, it is fully populated with null values (See setSize in DefaultListModel).
     */
    private static class DefaultListModelPD extends DefaultPersistenceDelegate {
        @Override
        protected void initialize(Class<?> type, Object oldInstance, Object newInstance, Encoder out) {
            // Note, the "size" property will be set here.
            super.initialize(type, oldInstance, newInstance, out);
            javax.swing.DefaultListModel oldDLM = (javax.swing.DefaultListModel) oldInstance;
            javax.swing.DefaultListModel newDLM = (javax.swing.DefaultListModel) newInstance;
            // At this point newDLM will be a list of null values
            // It should be the same size as oldDLM but play it safe anyway
            int oldSize = oldDLM.getSize();
            int newSize = newDLM.getSize();
            int maxSet = (newSize < oldSize ? newSize : oldSize);
            int i = 0;
            for (; i < maxSet; i++) {
                out.writeStatement(new Statement(oldInstance, "set", new Object[]{i, oldDLM.getElementAt(i)}));
                // Mutate the newInstance to match the oldInstance
                newDLM.set(i, oldDLM.getElementAt(i));
            }
            for (; i < oldSize; i++) {
                out.writeStatement(new Statement(oldInstance, "add", // Can also use "addElement".
                        new Object[]{oldDLM.getElementAt(i)}));
                // Mutate the newInstance to match the oldInstance
                newDLM.add(i, oldDLM.getElementAt(i));
            }
        }
    }


    private synchronized LocalIO getLocalIO() {
        if (localIO == null) {
            localIO = getPersistenceServiceIO();
            if (localIO == null) {
                localIO = new LocalFileIO();
            }
        }
        return localIO;
    }

    private abstract class LocalIO {

        /**
         * Opens an input stream to read from the entry
         * specified by the {@code name} parameter.
         * If the named entry cannot be opened for reading
         * then a {@code IOException} is thrown.
         *
         * @param fileName the storage-dependent name
         * @return an {@code InputStream} object
         * @throws IOException if the specified name is invalid,
         *                     or an input stream cannot be opened
         */
        public abstract InputStream openInputFile(String fileName) throws IOException;


        /**
         * Opens an output stream to write to the entry
         * specified by the {@code name} parameter.
         * If the named entry cannot be opened for writing
         * then a {@code IOException} is thrown.
         * If the named entry does not exist it can be created.
         * The entry will be recreated if already exists.
         *
         * @param fileName the storage-dependent name
         * @return an {@code OutputStream} object
         * @throws IOException if the specified name is invalid,
         *                     or an output stream cannot be opened
         */
        public OutputStream openOutputFile(final String fileName) throws IOException {
            return openOutputFile(fileName, false);
        }


        /**
         * Opens an output stream to write to the entry
         * specified by the {@code name} parameter.
         * If the named entry cannot be opened for writing
         * then a {@code IOException} is thrown.
         * If the named entry does not exist it can be created.
         * You can decide whether data will be appended via append parameter.
         *
         * @param fileName the storage-dependent name
         * @param append   if <code>true</code>, then bytes will be written
         *                 to the end of the output entry rather than the beginning
         * @return an {@code OutputStream} object
         * @throws IOException if the specified name is invalid,
         *                     or an output stream cannot be opened
         */
        public abstract OutputStream openOutputFile(final String fileName, boolean append) throws IOException;

        /**
         * Deletes the entry specified by the {@code name} parameter.
         *
         * @param fileName the storage-dependent name
         * @throws IOException if the specified name is invalid,
         *                     or an internal entry cannot be deleted
         */
        public abstract boolean deleteFile(String fileName) throws IOException;
    }

    private final class LocalFileIO extends LocalIO {

        @Override
        public InputStream openInputFile(String fileName) throws IOException {
            File path = getFile(fileName);
            try {
                return new BufferedInputStream(new FileInputStream(path));
            } catch (IOException e) {
                throw new IOException("couldn't open input file \"" + fileName + "\"", e);
            }
        }

        @Override
        public OutputStream openOutputFile(String name, boolean append) throws IOException {
            try {
                File file = getFile(name);
                File dir = file.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("couldn't create directory " + dir);
                }
                return new BufferedOutputStream(new FileOutputStream(file, append));
            } catch (SecurityException exception) {
                throw new IOException("could not write to entry: " + name, exception);
            }
        }

        @Override
        public boolean deleteFile(String fileName) throws IOException {
            File path = new File(getDirectory(), fileName);
            return path.delete();
        }

        private File getFile(String name) throws IOException {
            if (name == null) {
                throw new IOException("name is not set");
            }
            return new File(getDirectory(), name);
        }

    }

    /* Determine if we're a web started application and the
     * JNLP PersistenceService is available without forcing
     * the JNLP API to be class-loaded.  We don't want to
     * require apps that aren't web started to bundle javaws.jar
     */
    private LocalIO getPersistenceServiceIO() {
        try {
            Class smClass = Class.forName("javax.jnlp.ServiceManager");
            Method getServiceNamesMethod = smClass.getMethod("getServiceNames");
            String[] serviceNames = (String[]) getServiceNamesMethod.invoke(null);
            boolean psFound = false;
            boolean bsFound = false;
            for (String serviceName : serviceNames) {
                if (serviceName.equals("javax.jnlp.BasicService")) {
                    bsFound = true;
                } else if (serviceName.equals("javax.jnlp.PersistenceService")) {
                    psFound = true;
                }
            }
            if (bsFound && psFound) {
                return new PersistenceServiceIO();
            }
        } catch (Exception ignore) {
            // either the classes or the services can't be found
        }
        return null;
    }

    private final class PersistenceServiceIO extends LocalIO {


        private String initFailedMessage(String s) {
            return getClass().getName() + " initialization failed: " + s;
        }

        PersistenceServiceIO() {

        }

        private void checkBasics(String s) throws IOException {

        }

        private URL fileNameToURL(String name) throws IOException {
            if (name == null) {
                throw new IOException("name is not set");
            }
            return null;

        }

        @Override
        public InputStream openInputFile(String fileName) throws IOException {
            checkBasics("openInputFile");
            URL fileURL = fileNameToURL(fileName);
            throw new IOException("openInputFile ");
        }

        @Override
        public OutputStream openOutputFile(String fileName, boolean append) throws IOException {
            checkBasics("openOutputFile");
            throw new IOException("openOutputFile not supported");
        }

        @Override
        public boolean deleteFile(String fileName) throws IOException {
            checkBasics("deleteFile");
            URL fileURL = fileNameToURL(fileName);
            try {
                return true;
            } catch (Exception e) {
                throw new IOException("openInputFile \"" + fileName + "\" failed", e);
            }
        }
    }


    static class PrimitivePersistenceDelegate extends PersistenceDelegate {
        @Override
        protected Expression instantiate(Object oldInstance, Encoder out) {
            return new Expression(oldInstance, oldInstance.getClass(),
                    "new", new Object[]{oldInstance.toString()});
        }
    }

}
