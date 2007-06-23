/*
 * Copyright 2005 The Apache Software Foundation.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.vafer.dependency.relocation;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.vafer.dependency.Console;
import org.vafer.dependency.asm.RenamingVisitor;
import org.vafer.dependency.asm.RuntimeWrappingClassAdapter;


public final class JarProcessor {

	private static final class MappingEntry {
		private final String oldName;
		private final String newName;
		private Version[] versions;
		
		public MappingEntry( final String pOldName, final String pNewName, final Version pVersion ) {
			oldName = pOldName;
			newName = pNewName;
			versions = new Version[] { pVersion };
		}
		
		public void addVersion( final Version pVersion ) {
			final Version[] newVersions = new Version[versions.length+1];
			System.arraycopy(versions, 0, newVersions, 1, versions.length);
			newVersions[0] = pVersion;
			versions = newVersions;
		}
		
		public Version[] getVersions() {
			return versions;
		}
		
		public String getOldName() {
			return oldName;
		}

		public String getNewName() {
			return newName;
		}
		
		public boolean isMappingRequired() {
			return !oldName.equals(newName);
		}
		
		public String toString() {
			return oldName;
		}
	}

	
	private final Console console;
	
	public JarProcessor() {
		this(new Console() {
			public void println(String pString) {
			}			
		});
	}
	
	public JarProcessor( final Console pConsole ) {
		console = pConsole;
	}
	
	private static byte[] calculateDigest( final MessageDigest digest, final InputStream inputStream ) throws IOException {
        digest.reset();
        final DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest);                
        IOUtils.copy(digestInputStream, new NullOutputStream());
        return digest.digest();
	}
	
	
	private Map getMapping( final Jar[] pJars ) throws IOException, NoSuchAlgorithmException {
        
		final MessageDigest digest = MessageDigest.getInstance("MD5");
        
        final Map byOldName = new HashMap(pJars.length*50);
        
        for (int i = 0; i < pJars.length; i++) {
        	final Jar jar = pJars[i];

        	final JarInputStream inputStream = jar.getInputStream();

            while (true) {
                final JarEntry entry = inputStream.getNextJarEntry();
                
                if (entry == null) {
                    break;
                }

                final String oldName = entry.getName();                
                final String newName = jar.getPrefix() + oldName;
                
                final byte[] digestBytes = calculateDigest(digest, inputStream);                

        		final MappingEntry mappingEntry = (MappingEntry) byOldName.get(newName);
        		if (mappingEntry == null) {
        			// a new resource
            		byOldName.put(oldName, new MappingEntry(oldName, newName, new Version(jar, digestBytes)));
        		} else {
        			// a new version of resource seen before
        			mappingEntry.addVersion(new Version(jar, digestBytes));
        		}
            }
            
            IOUtils.closeQuietly(inputStream);
        }        
		
        return byOldName;
	}
	
	
	public void processJars( final Jar[] pJars, final ResourceHandler pHandler, final FileOutputStream pOutput ) throws IOException, NoSuchAlgorithmException {

        final Map resourcesByName = getMapping(pJars);
        final Map finalMapping = new HashMap();
                
        final JarOutputStream outputStream = new JarOutputStream(pOutput);

        console.println("Building new jar with mappings:");
        boolean mappingRequired = false;
		for (Iterator it = resourcesByName.values().iterator(); it.hasNext();) {
			final MappingEntry mappingEntry = (MappingEntry) it.next();			
			console.println(" " + mappingEntry.getOldName() + " -> " + mappingEntry.getNewName() + " [" + mappingEntry.versions.length + "]");
			if (mappingEntry.isMappingRequired()) {
				mappingRequired = true;
			}
		}
		final String localMapperName = "org/vafer/dependency/RuntimeMapper";

        pHandler.onStartProcessing(outputStream);
        
        final ResourceRenamer renamer = new ResourceRenamer() {
			public String getNewNameFor( String pResourceName ) {
				
				final MappingEntry mappingEntry = (MappingEntry) resourcesByName.get(pResourceName);

				if (mappingEntry == null) {
					return pResourceName;
				}

				return mappingEntry.newName;
			}        	
        };

        for (int i = 0; i < pJars.length; i++) {
        	final Jar jar = pJars[i];

        	pHandler.onStartJar(jar, outputStream);
        	
            final JarInputStream inputStream = jar.getInputStream();

            while (true) {
                final JarEntry entry = inputStream.getNextJarEntry();
                
                if (entry == null) {
                    break;
                }
                
                if (entry.isDirectory()) {
                    // ignore directory entries
                	IOUtils.copy(inputStream, new NullOutputStream());
                    continue;
                }

                final MappingEntry mapping = (MappingEntry) resourcesByName.get(entry.getName());
                final String oldName = mapping.getOldName();
                final String newName = mapping.getNewName();
                final Version[] versions = mapping.getVersions();
                
                finalMapping.put(oldName, newName);
                
                final InputStream newInputStream = pHandler.onResource(jar, oldName, newName, versions, inputStream);
                
                if (newInputStream == null) {
                	// remove the resource
                	IOUtils.copy(inputStream, new NullOutputStream());
                	continue;
                }

                outputStream.putNextEntry(new JarEntry(newName));                    

                if (newName.endsWith(".class") && mappingRequired) {
                    final byte[] oldClassBytes = IOUtils.toByteArray(newInputStream);

                    final ClassReader r = new ClassReader(oldClassBytes);
                    final ClassWriter w = new ClassWriter(true);
                    
                    r.accept(new RenamingVisitor(new RuntimeWrappingClassAdapter(w, localMapperName), renamer), false);                    	

                    final byte[] newClassBytes = w.toByteArray();
                    IOUtils.copy(new ByteArrayInputStream(newClassBytes), outputStream);
                    continue;
                }

                IOUtils.copy(newInputStream, outputStream);                                                
            }

            
            IOUtils.closeQuietly(inputStream);

        	pHandler.onStopJar(jar, outputStream);

        }

        pHandler.onStopProcessing(outputStream);

        if (mappingRequired) {
	        console.println("Creating runtime mapper " + localMapperName);
	    	
	        outputStream.putNextEntry(new JarEntry(localMapperName + ".class"));
	        try {
				final byte[] clazzBytes = MapperDump.dump(localMapperName, finalMapping);
	            IOUtils.copy(new ByteArrayInputStream(clazzBytes), outputStream);					
			} catch (Exception e) {
				throw new IOException("Could not generate mapper class " + e);
			}
        }
        
        IOUtils.closeQuietly(outputStream);
	}
	
}
