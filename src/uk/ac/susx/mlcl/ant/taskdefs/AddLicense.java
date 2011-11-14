/*
 * Copyright (c) 2010 - 2011, University of Sussex
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 * 
 *  * Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 *  * Redistributions in binary form must reproduce the above copyright notice, 
 *    this list of conditions and the following disclaimer in the documentation 
 *    and/or other materials provided with the distribution.
 * 
 *  * Neither the name of the University of Sussex nor the names of its 
 *    contributors may be used to endorse or promote products  derived from this
 *    software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY DIRECT, 
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY 
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING 
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, 
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package uk.ac.susx.mlcl.ant.taskdefs;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;

import org.apache.tools.ant.Task;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.taskdefs.LoadFile;

/**
 *
 * @author Matti Lyra &lt;m.lyra at sussex.ac.uk&gt;
 */
public class AddLicense
    extends Task
{
    private String licenseFile;
    private ArrayList<Path> paths = new ArrayList<Path>();

    @Override
    public void execute()
            throws BuildException
    {
        System.out.println("Adding license to source files");
        System.out.println("License file: " + licenseFile);
        
        if (licenseFile == null || licenseFile.isEmpty())
            throw new BuildException("License file not set.");
        
        File license = new File(licenseFile);
        if (!license.exists())
            throw new BuildException("License file does not exist.");
        
        if (!license.canRead())
            throw new BuildException("Can not read license file.");
        
        if (paths.isEmpty())
            throw new BuildException("No source files set to be checked.");
        
        // get the text of the license file
        LoadFile loadfile = new LoadFile();
        loadfile.setProperty("license.text");
        loadfile.setSrcFile(license);
        loadfile.setProject(getProject());
        loadfile.execute();
        final String licenseText = getProject().getProperty("license.text");
        
        // transform the text into a javadoc comment
        final String[] licenseLines = licenseText.split("\n");
        final StringBuilder licenseBuilder = new StringBuilder();
        licenseBuilder.append("/*\n");
        for (int i = 0; i < licenseLines.length; i++) {
            String line = String.format(" * %s\n", licenseLines[i]);
            licenseBuilder.append(line);
            licenseLines[i] = line;
        }
        
        licenseBuilder.append(" */\n");
        final String licenseJavadoc = licenseBuilder.toString();
        
        final String srcDir = getProject().getProperty("src.dir");
        File fhandle;
        FileWriter fout;
        
        // go through the list of files
        StringBuilder srcBody = new StringBuilder();
        for(Iterator<Path> iter = paths.iterator(); iter.hasNext(); ) {
            final Path path = iter.next();
            final String[] includedFiles = path.list();
            
            for(int i=0; i < includedFiles.length; i++) {
                final String filepath = includedFiles[i].replace('\\','/');
                final int pathLength = filepath.lastIndexOf("/")+1;
                
                // extract package name from the file path
                String folder = filepath.substring(0, pathLength);
                folder = folder.substring(folder.lastIndexOf(srcDir) + srcDir.length() + 1, folder.length() - 1);
                final String packagename = String.format("package %s;", folder.replace("/", "."));
                
                // extract class name from the file path
                final String classname = filepath.substring(pathLength);
                
                // load the file contents
                fhandle = new File(filepath);
                loadfile.setProperty(classname.toLowerCase());
                loadfile.setSrcFile(fhandle);
                loadfile.execute();
                
                String srcFile = getProject().getProperty(classname.toLowerCase());
                LineNumberReader srcReader  = new LineNumberReader(new StringReader(srcFile));
                
                String line;
                boolean header = true; // while reading the header (before package declaration)
                boolean replaceHeader = false; // we expect the license to be in the files
                int licenseIter = 0;
                boolean srcMatches = true;
                try {
                    while ((line = srcReader.readLine()) != null) {
                        if (line.equals(packagename)) {
                            // no more header
                            header = false;
                            if (licenseIter == 0)
                                // if no licese information was found we need to add it
                                replaceHeader = true;
                        }
                        
                        /* don't bother with the start and end lines of the
                         * license header or lines that only contain whitespace
                         */
                        if (header && (line.startsWith("/*") || line.startsWith("*/") || line.trim().isEmpty()))
                            continue;
                        
                        if (srcMatches && header) {
                            /* We are dealing with the header and all the
                             * previous lines have matched the license text.
                             */
                            srcMatches = licenseLines[licenseIter++].contentEquals(line);
                            if (!srcMatches)
                                replaceHeader = true;
                        } else if (!header && !replaceHeader) {
                            /* All the lines of the source file header match 
                             * those in the license so we don't need to do
                             * anything to this file.
                             */
                            srcReader.close();
                            break;
                        } else if (!header) {
                            /* Read in the meat of the document so that we can
                             * later prepend the license to it if needed.
                             */
                            srcBody.append(String.format("%s\n", line));
                        } else {
                            /* Header was read but the license was found to not
                             * match that of the license file. Read over the
                             * rest of the header and then replace it.
                             */
                        }
                    }
                    srcReader.close();
                    
                } catch (IOException ioex) {
                    throw new BuildException(String.format("Cannot read source file (%s.%s)", packagename, classname));
                }
                
                if (replaceHeader) {
                    srcBody.insert(0, licenseJavadoc);
                    try {
                        fout = new FileWriter(fhandle);
                        fout.write(srcBody.toString());
                        fout.flush();
                        fout.close();
                    } catch (IOException ioex) {
                        throw new BuildException(String.format("Cannot write to file (%s.%s)", packagename, classname));
                    }
                }
            }
        }
    }
    
    public void addPath(Path fileset) {
        paths.add(fileset);
    }
    
    public void setLicenseFile (String fileName)
    {
        this.licenseFile = fileName;
    }
}
