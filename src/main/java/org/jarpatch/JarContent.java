/* jarpatch - http://perso.club-internet.fr/sjobic/jarpatch/
 * Copyright (C) 2004 Norbert Barbosa (norbert.barbosa@laposte.net)
 *
 * An utility, available as standalone application and Ant task,
 * to build zip patch which correspond to the difference between 2 jar files
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA 
 */
package org.jarpatch;

import java.io.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * maintain a view of a Jar content.
 * Read a jar file, and compute a MD5 key for each file entry of the jar file
 *
 * @author Norbert Barbosa
 * @version $Revision$
 */
public class JarContent {
    private final JarFile fjar;
    private Map fcontents;
    private static MessageDigest fmd5Digest;
    private Pattern[] fexcludes;
    
    private static final JarEntry[] EMPTY_JARENTRIES = {};

    /** build a new JarContent from the given jar file */
    public JarContent(File jar) throws IOException {
        fjar = new JarFile(jar);
    }
    
    /** set the exclude pattern */
    public void setExcludePattern(Pattern[] excludes) {
        fexcludes = excludes;
    }

    /** initialize the JarContent from the jar */
    public void initializeContent() throws IOException {
        fcontents = new HashMap(1000);
        for(Enumeration e=fjar.entries(); e.hasMoreElements(); ){
            JarEntry entry = (JarEntry)e.nextElement();
            if(fexcludes != null && fexcludes.length != 0){
                boolean skip=false;
                for(int i=0;i<fexcludes.length;i++)
                    if(fexcludes[i].matcher(entry.getName()).matches()){
                        skip = true;
                        break;
                    }
                if(skip)
                    continue;
            }
            InputStream in = null;
            try {
                in = fjar.getInputStream(entry);
                byte[] md5 = computeMd5(in);
                fcontents.put(entry.getName(), md5);
            }finally{
                if(in != null) in.close();
            }
        }
    }
    /** compute the md5 hash for the given jar file */
    private byte[] computeMd5(InputStream in) throws IOException{
        try{
            if(fmd5Digest == null)
                fmd5Digest = MessageDigest.getInstance("MD5");
            in = new DigestInputStream(in, fmd5Digest);
            byte[] buf = new byte[2048];
            while(in.read(buf) != -1){/*nothing*/}
            return fmd5Digest.digest();
        }catch(Exception e){
            throw new IOException(e.getMessage());
        }
    }

    /**
     * Extracts a jar or zip entry to a temp file which can be used for
     * additional comparison
     *
     * @param entry the jar, zip, or war entry
     * @return the temp file of the extracted file
     * @throws IOException if an error occurs during the extraction
     */
    public File extractJarEntry(String entry) throws IOException
    {
        File result = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            JarEntry jentry = fjar.getJarEntry(entry);
            if (jentry != null) {
                is = fjar.getInputStream(jentry);
                String[] array = entry.split("/");
                if (array != null && array.length > 0) {
                    File tmpFile = File.createTempFile("jarpatch_" +
                                                       array[array.length - 1],
                                                       ".jar");
                    if (tmpFile != null) {
                        tmpFile.deleteOnExit();
                        os = new FileOutputStream(tmpFile);

                        int nb;
                        byte[] data = new byte[8 * 1024];
                        while ((nb = is.read(data)) > 0) {
                            os.write(data, 0, nb);
                        }

                        result = tmpFile;
                    }
                }
            }

        } finally {
            if (os != null) {
                os.close();
            }
            if (is != null) {
                is.close();
            }
        }

        return result;
    }

    /**
     * Return the number of entries within a jar file
     *
     * @return the number of entries
     */
    public int getNumberOfJarEntries() {
        return fjar.size();
    }
    
    /** return all the JarEntry that have been modified from the old jar,
     * or EMPTY_JARENTRY.
     */
    public JarEntry[] computeNewerEntry(JarContent oldJar) throws IOException {
        List ret = null;
        for(Iterator i = fcontents.entrySet().iterator(); i.hasNext(); ){
            boolean foundDifference = false;
            Map.Entry e = (Map.Entry)i.next();
            String entry = (String)e.getKey();
            if(entry.startsWith("META-INF") /*|| entry.startsWith("WEB-INF")*/) // skip manifest content
                continue;

            if (entry.endsWith(".jar") || entry.endsWith(".war") || entry.endsWith(".zip")) {
                File newJarFile = extractJarEntry(entry);
                File oldJarFile = oldJar.extractJarEntry(entry);
                if (newJarFile != null && oldJarFile != null) {
                    JarContent nj = new JarContent(newJarFile);
                    JarContent oj = new JarContent(oldJarFile);
                    if (fexcludes != null) {
                        nj.setExcludePattern(fexcludes);
                        oj.setExcludePattern(fexcludes);
                    }
                    nj.initializeContent();
                    oj.initializeContent();
                    int newsize = nj.getNumberOfJarEntries();
                    int oldsize = oj.getNumberOfJarEntries();

                    if (newsize != oldsize) {
                        foundDifference = true;
                    } else {
                        JarEntry[] diff = nj.computeNewerEntry(oj);
                        if (diff != null && diff.length > 0) {
                            foundDifference = true;
                        }
                    }
                } else if (oldJarFile == null && newJarFile != null) {
                    foundDifference = true;
                }
            } else {
                byte[] md5 = (byte[])e.getValue();
                byte[] oldmd5 = (byte[])oldJar.fcontents.get(entry);
                if(oldmd5 == null || !Arrays.equals(md5, oldmd5)){
                    foundDifference = true;
                }
            }
            if (foundDifference) {
                if(ret == null) ret = new ArrayList();
                ret.add(fjar.getJarEntry(entry));
            }
        }
        if(ret == null) return EMPTY_JARENTRIES;
        JarEntry[] aret = new JarEntry[ret.size()];
        ret.toArray(aret);
        return aret;
    }


    /*
     * Return a List of entries that were deleted as of the newJar.  That is, the List computed and returned will contain those entries present in the oldJar but not in newJar.  The List will be empty if there are no deleted entries as of the newJar.
     * @return the number of entries deleted from oldJar to newJar
     *
     */
    public List computeDeletedEntry(JarContent newJar) throws IOException {
        ArrayList deldiff = new ArrayList();

	    // note that the loop is iterating over oldJar and making comparisons to newJar; this is inverse of function "computeNewerEntry( )"
        for(Iterator i = fcontents.entrySet().iterator(); i.hasNext(); ){
            boolean foundDifference = false;
            Map.Entry e = (Map.Entry)i.next();
            String entry = (String)e.getKey();
            if (entry.startsWith("META-INF") /*|| entry.startsWith("WEB-INF")*/) // skip manifest content
                continue;

            if (entry.endsWith(".jar") || entry.endsWith(".war") || entry.endsWith(".zip")) {
		    // entry is a JAR, WAR, or ZIP
                File oldJarFile = extractJarEntry(entry);
                File newJarFile = newJar.extractJarEntry(entry);
                if (newJarFile == null && oldJarFile != null) {
                    // file exists in oldJar but not in newJar
                    // JAR, WAR, and ZIP files are treated atomically; that is, only considers the archive file itself as a single unit and will not identify composing content files as deleted or modified
                    foundDifference = true;
                }
            } else {
		        // entry is not a JAR, WAR, or ZIP
                if(!newJar.fcontents.containsKey(entry)) {
		            // file exists in oldJar but not in newJar
                    foundDifference = true;
                }
            }
            if (foundDifference) {
		        // found a file that exists in newJar but no in oldJar; so add to return List
                deldiff.add(entry);
            }
        }
        return deldiff;
    }


    public void writeEntry(JarEntry jarEntry, OutputStream out) throws IOException {
        byte[] buf = new byte[2048];
        int len;
        // TODO: should I close the stream????
        InputStream in = fjar.getInputStream(jarEntry);
        while((len = in.read(buf)) != -1){
            out.write(buf, 0, len);
        }
    }
}
