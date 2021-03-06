/*
 * Copyright (C) 2011 Nipuna Gunathilake.
 * All rights reserved.
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

package edu.usf.cutr.gtfsrtvalidator.api.resource;

import com.conveyal.gtfs.model.InvalidValue;
import com.conveyal.gtfs.validator.json.FeedProcessor;
import com.conveyal.gtfs.validator.json.FeedValidationResult;
import com.conveyal.gtfs.validator.json.FeedValidationResultSet;
import com.conveyal.gtfs.validator.json.backends.FileSystemFeedBackend;
import com.conveyal.gtfs.validator.json.serialization.JsonSerializer;
import edu.usf.cutr.gtfsrtvalidator.db.GTFSDB;
import edu.usf.cutr.gtfsrtvalidator.helper.GetFile;
import edu.usf.cutr.gtfsrtvalidator.lib.model.GtfsFeedModel;
import org.hibernate.Session;
import org.onebusaway.gtfs.impl.GtfsDaoImpl;
import org.onebusaway.gtfs.serialization.GtfsReader;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import javax.ws.rs.*;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static edu.usf.cutr.gtfsrtvalidator.helper.HttpMessageHelper.generateError;

@Path("/gtfs-feed")
public class GtfsFeed {

    private static final org.slf4j.Logger _log = LoggerFactory.getLogger(GtfsFeed.class);

    private static final int BUFFER_SIZE = 4096;
    private static final String jsonFilePath = "classes"+File.separator+"webroot";
    public static Map<Integer, GtfsDaoImpl> GtfsDaoMap = new ConcurrentHashMap<>();

    //DELETE {id} remove feed with the given id
    @DELETE
    @Path("/{id}")
    public Response deleteGtfsFeed(@PathParam("id") String id) {
        Session session = GTFSDB.initSessionBeginTrans();
        session.createQuery("DELETE FROM GtfsFeedModel WHERE feedID = "+ id).executeUpdate();
        GTFSDB.commitAndCloseSession(session);
        return Response.accepted().build();
    }

    //GET return list of available gtfs-feeds
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getGtfsFeeds() {
        List<GtfsFeedModel> gtfsFeeds = new ArrayList<>();
        try {
            Session session = GTFSDB.initSessionBeginTrans();
            List<GtfsFeedModel> tempGtfsFeeds = session.createQuery(" FROM GtfsFeedModel").list();
            GTFSDB.commitAndCloseSession(session);
            if (tempGtfsFeeds != null) {
                gtfsFeeds = tempGtfsFeeds;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        GenericEntity<List<GtfsFeedModel>> feedList = new GenericEntity<List<GtfsFeedModel>>(gtfsFeeds) {
        };
        return Response.ok(feedList).build();
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response postGtfsFeed(@FormParam("gtfsurl") String gtfsFeedUrl,
                                 @FormParam("enablevalidation") String enableValidation) {

        //Extract the URL from the provided gtfsFeedUrl
        URL url = getUrlFromString(gtfsFeedUrl);
        if (url == null) {
            return generateError("Malformed URL", "Malformed URL for the GTFS feed.", Response.Status.BAD_REQUEST);
        }

        _log.info(String.format("Downloading GTFS data from %s...", url));

        HttpURLConnection connection = null;
        //Open a connection for the given URL u
        try {
            connection = (HttpURLConnection)url.openConnection();
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        if (connection == null) {
            return generateError("Can't read from URL", "Failed to establish a connection to the GTFS URL.", Response.Status.BAD_REQUEST);
        }

        String saveFileName = null;
        try {
            saveFileName = URLEncoder.encode(gtfsFeedUrl, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        boolean canReturn = false;
        //Read gtfsFeedModel with the same URL in the database        
        Session session = GTFSDB.initSessionBeginTrans();
        GtfsFeedModel gtfsFeed = (GtfsFeedModel) session.createQuery("FROM GtfsFeedModel "
                            + "WHERE gtfsUrl = '"+gtfsFeedUrl+"'").uniqueResult();
        Response.Status response = downloadGtfsFeed(saveFileName, connection);
        if (response == Response.Status.BAD_REQUEST) {
            return generateError("Download Failed", "Downloading static GTFS feed from provided Url failed.", Response.Status.BAD_REQUEST);
        } else if (response == Response.Status.FORBIDDEN) {
            return generateError("SSL Handshake Failed", "SSL handshake failed.  Try installing the JCE Extension - see https://github.com/CUTR-at-USF/gtfs-realtime-validator#prerequisites", Response.Status.FORBIDDEN);
        }

        _log.info("GTFS data downloaded successfully");

        //TODO: Move to one method
        if (gtfsFeed == null) {
            gtfsFeed = createGtfsFeedModel(gtfsFeedUrl, saveFileName);
        } else {
            _log.info("GTFS URL already exists exists in database - checking if data has changed...");
            byte[] newChecksum = calculateMD5checksum(gtfsFeed.getFeedLocation());
            byte[] oldChecksum = gtfsFeed.getChecksum();
            // If file digest are equal, check whether validated json file exists
            if (MessageDigest.isEqual(newChecksum, oldChecksum)) {
                _log.info("GTFS data hasn't changed since last execution");
                String projectPath = new GetFile().getJarLocation().getParentFile().getAbsolutePath();
                if (new File(projectPath + File.separator + jsonFilePath + File.separator + saveFileName + "_out.json").exists())
                    canReturn = true;
            } else {
                _log.info("GTFS data has changed, updating database...");
                gtfsFeed.setChecksum(newChecksum);
                updateGtfsFeedModel(gtfsFeed);
            }
        }

        //Saves GTFS data to store and validates GTFS feed
        GtfsDaoImpl store = saveGtfsFeed(gtfsFeed);
        if (store == null) {
            return generateError("Can't read content", "Can't read content from the GTFS URL", Response.Status.NOT_FOUND);
        }
        // Save gtfs agency to the database
        gtfsFeed.setAgency(store.getAllAgencies().iterator().next().getTimezone());
        session.update(gtfsFeed);
        GTFSDB.commitAndCloseSession(session);

        GtfsDaoMap.put(gtfsFeed.getFeedId(), store);
        
        if(canReturn)
            return Response.ok(gtfsFeed).build();
        
        if ("checked".equalsIgnoreCase(enableValidation)) {
            return runStaticGTFSValidation(saveFileName, gtfsFeedUrl, gtfsFeed);
        }
       return Response.ok(gtfsFeed).build();
    }

    private Response runStaticGTFSValidation (String saveFileName, String gtfsFeedUrl, GtfsFeedModel gtfsFeed) {
        FileSystemFeedBackend backend = new FileSystemFeedBackend();
        FeedValidationResultSet results = new FeedValidationResultSet();
        File input = backend.getFeed(saveFileName);
        FeedProcessor processor = new FeedProcessor(input);
        try {
            _log.info("Running static GTFS validation on " + gtfsFeedUrl + "...");
            processor.run();
        } catch (IOException ex) {
            Logger.getLogger(GtfsFeed.class.getName()).log(Level.SEVERE, null, ex);
            return generateError("Unable to access input GTFS " + input.getPath() + ".", "Does the file " + saveFileName + "exist and do I have permission to read it?", Response.Status.NOT_FOUND);
        }
        results.add(processor.getOutput());
        saveGtfsErrorCount(gtfsFeed, processor.getOutput());
        JsonSerializer serializer = new JsonSerializer(results);
        //get the location of the executed jar file
        GetFile jarInfo = new GetFile();
        String saveDir = jarInfo.getJarLocation().getParentFile().getAbsolutePath();
        saveFileName = saveDir + File.separator + jsonFilePath + File.separator + saveFileName + "_out.json";
        try {
            serializer.serializeToFile(new File(saveFileName));
            _log.info("Static GTFS validation data written to " + saveFileName);
        } catch (Exception e) {
            _log.error("Exception running static GTFS validation on " + gtfsFeedUrl + ": " + e.getMessage());
        }
        return Response.ok(gtfsFeed).build();
    }

    //Gets URL from string returns null if failed to parse URL
    private URL getUrlFromString(String urlString) {
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException ex) {
            _log.error("Invalid URL", ex);
            url = null;
        }

        return url;
    }

    private HttpURLConnection getHttpURLConnection(URL url) {
        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11");
            connection.connect();

            //Check if the request is handled successfully
            if (connection.getResponseCode() / 100 == 2) {
                connection = null;
            }

        } catch (IOException ex) {
            _log.error("Can't read from GTFS URL", ex);
            connection = null;
        }
        return connection;
    }

    private GtfsFeedModel createGtfsFeedModel(String gtfsFeedUrl, String saveFilePath) {
        GtfsFeedModel gtfsFeed;
        gtfsFeed = new GtfsFeedModel();
        gtfsFeed.setFeedLocation(saveFilePath);
        gtfsFeed.setGtfsUrl(gtfsFeedUrl);
        gtfsFeed.setStartTime(System.currentTimeMillis());
        
        byte[] checksum = calculateMD5checksum(saveFilePath);
        gtfsFeed.setChecksum(checksum);

        //Create GTFS feed row in database
        Session session = GTFSDB.initSessionBeginTrans();
        session.save(gtfsFeed);
        GTFSDB.commitAndCloseSession(session);
        return gtfsFeed;
    }

    private GtfsFeedModel updateGtfsFeedModel(GtfsFeedModel gtfsFeed) {        
        //Update GTFS feed row in database
        Session session = GTFSDB.initSessionBeginTrans();
        session.update(gtfsFeed);
        GTFSDB.commitAndCloseSession(session);
        return gtfsFeed;
    }
    private byte[] calculateMD5checksum(String inputFile) {
        byte[] digest = null;
        byte[] dataBytes = new byte[1024];
        int nread;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try {
                InputStream is = Files.newInputStream(Paths.get(inputFile));
                while ((nread = is.read(dataBytes)) != -1)
                    md.update(dataBytes, 0, nread);
                } catch (IOException ex) {
                    Logger.getLogger(GtfsFeed.class.getName()).log(Level.SEVERE, null, ex);
                }
            digest = md.digest();
            }   catch (NoSuchAlgorithmException ex) {
                Logger.getLogger(GtfsFeed.class.getName()).log(Level.SEVERE, null, ex);
            }
        return digest;
    }
    private GtfsDaoImpl saveGtfsFeed(GtfsFeedModel gtfsFeed) {
        GtfsDaoImpl store = new GtfsDaoImpl();

        try {
            //Read GTFS data into a GtfsDaoImpl
            GtfsReader reader = new GtfsReader();
            reader.setInputLocation(new File(gtfsFeed.getFeedLocation()));

            reader.setEntityStore(store);
            reader.run();
        } catch (Exception ex) {
            return null;
        }
        return store;
    }

    private Response.Status downloadGtfsFeed(String saveFilePath, HttpURLConnection connection) {
        try {
            // Set user agent (#320)
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11");

            // Check for HTTP 301 redirect
            String redirect = connection.getHeaderField("Location");
            if (redirect != null) {
                _log.warn("Redirecting to " + redirect);
                connection = (HttpURLConnection) new URL(redirect).openConnection();
            }

            // Opens input stream from the HTTP(S) connection
            InputStream inputStream;
            try {
                inputStream = connection.getInputStream();
            } catch (SSLHandshakeException sslEx) {
                _log.error("SSL handshake failed.  Try installing the JCE Extension - see https://github.com/CUTR-at-USF/gtfs-realtime-validator#prerequisites", sslEx);
                return Response.Status.FORBIDDEN;
            }

            // opens an output stream to save into file
            FileOutputStream outputStream = new FileOutputStream(saveFilePath);

            int bytesRead;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.close();
            inputStream.close();
        } catch (IOException ex) {
            _log.error("Downloading GTFS Feed Failed", ex);
            return Response.Status.BAD_REQUEST;
        }
        return Response.Status.OK;
    }

    private void saveGtfsErrorCount(GtfsFeedModel gtfsFeedModel, FeedValidationResult result) {

        int errorCount = 0;
        for (InvalidValue invalidValue: result.routes.invalidValues) {
            errorCount++;
        }
        for (InvalidValue invalidValue: result.shapes.invalidValues) {
            errorCount++;
        }
        for (InvalidValue invalidValue: result.stops.invalidValues) {
            errorCount++;
        }
        for (InvalidValue invalidValue: result.trips.invalidValues) {
            errorCount++;
        }

        gtfsFeedModel.setErrorCount(errorCount);
        Session session = GTFSDB.initSessionBeginTrans();
        session.update(gtfsFeedModel);
        GTFSDB.commitAndCloseSession(session);
    }

    @GET
    @Path("/{id : \\d+}/errorCount")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFeedErrorCount(@PathParam("id") int id) {
        Session session = GTFSDB.initSessionBeginTrans();
        GtfsFeedModel gtfsFeed = (GtfsFeedModel) session.createQuery(" FROM GtfsFeedModel WHERE feedId = " + id).uniqueResult();
        GTFSDB.commitAndCloseSession(session);

        return Response.ok(gtfsFeed).build();
    }
}
