/*
 * Copyright 2018 FZI Forschungszentrum Informatik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.streampipes.connect.management.master;

import com.google.gson.Gson;
import org.apache.http.HttpEntity;
import org.apache.http.client.fluent.Request;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.streampipes.connect.adapter.exception.AdapterException;
import org.streampipes.model.connect.adapter.AdapterDescription;
import org.streampipes.model.connect.adapter.AdapterSetDescription;
import org.streampipes.model.connect.adapter.AdapterStreamDescription;
import org.streampipes.model.runtime.RuntimeOptionsResponse;
import org.streampipes.rest.shared.util.JsonLdUtils;
import org.streampipes.storage.couchdb.impl.AdapterStorageImpl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class WorkerRestClient {

    private static final Logger logger = LoggerFactory.getLogger(WorkerRestClient.class);


    public static void invokeStreamAdapter(String baseUrl, AdapterStreamDescription adapterStreamDescription) throws AdapterException {

        String url = baseUrl + "worker/stream/invoke";

        startAdapter(url, adapterStreamDescription);
    }

    public static void stopStreamAdapter(String baseUrl, AdapterStreamDescription adapterStreamDescription) throws AdapterException {
        String url = baseUrl + "worker/stream/stop";

        AdapterDescription ad = getAdapterDescriptionById(new AdapterStorageImpl(), adapterStreamDescription.getUri());

        stopAdapter(adapterStreamDescription.getId(), ad, url);
    }

    public static void invokeSetAdapter(String baseUrl, AdapterSetDescription adapterSetDescription) throws AdapterException {
        String url = baseUrl + "worker/set/invoke";

        startAdapter(url, adapterSetDescription);
    }

    public static void stopSetAdapter(String baseUrl, AdapterSetDescription adapterSetDescription) throws AdapterException {
        String url = baseUrl + "worker/set/stop";

        stopAdapter(adapterSetDescription.getUri(), adapterSetDescription, url);
    }

    public static void startAdapter(String url, AdapterDescription ad) throws AdapterException {
        try {
            logger.info("Trying to start adpater on endpoint: " + url);

            // this ensures that all adapters have a valid uri otherwise the json-ld serializer fails
            if (ad.getUri() == null) {
                ad.setUri("https://streampipes.org/adapter/" + UUID.randomUUID());
            }

            String adapterDescription = JsonLdUtils.toJsonLD(ad);

            String responseString = Request.Post(url)
                    .bodyString(adapterDescription, ContentType.APPLICATION_JSON)
                    .connectTimeout(1000)
                    .socketTimeout(100000)
                    .execute().returnContent().asString();

            logger.info("Adapter started on endpoint: " + url + " with Response: " + responseString);

        } catch (IOException e) {
            logger.error("Adapter did not start", e);
            throw new AdapterException("Adapter with URL: " + url + " did not start");
        }
    }


    public static void stopAdapter(String adapterId, AdapterDescription ad, String url) throws AdapterException {

        // Stop execution of adatper
        try {
            logger.info("Trying to stopAdapter adpater on endpoint: " + url);

            // TODO quick fix because otherwise it is not serialized to json-ld
            if (ad.getUri() == null) {
                logger.error("Adapter uri is null this should not happen " + ad);
            }

            String adapterDescription = JsonLdUtils.toJsonLD(ad);

            // TODO change this to a delete request
            String responseString = Request.Post(url)
                    .bodyString(adapterDescription, ContentType.APPLICATION_JSON)
                    .connectTimeout(1000)
                    .socketTimeout(100000)
                    .execute().returnContent().asString();

            logger.info("Adapter stopped on endpoint: " + url + " with Response: " + responseString);

        } catch (IOException e) {
            logger.error("Adapter was not stopped successfully", e);
            throw new AdapterException("Adapter was not stopped successfully with url: " + url);
        }

    }

    public static RuntimeOptionsResponse getConfiguration(String workerEndpoint, String elementId, String username, String runtimeOptionsRequest) throws AdapterException {
        String element = encodeValue(elementId);
        String url = workerEndpoint + "api/v1/" + username + "/worker/resolvable/" + element + "/configurations";
//        url = encodeValue(url);
//        String url = workerEndpoint + "/api/v1/" + username + "/worker/resolvable/abc/configurations";

        try {
            String responseString = Request.Post(url)
                       .bodyString(runtimeOptionsRequest, ContentType.APPLICATION_JSON)
                       .connectTimeout(1000)
                       .socketTimeout(100000)
                       .execute().returnContent().asString();

            return JsonLdUtils.fromJsonLd(responseString, RuntimeOptionsResponse.class);

        } catch (IOException e) {
            e.printStackTrace();
            throw new AdapterException("Could not resolve runtime configurations from " + url);
        }

    }

    public static String saveFileAtWorker(String baseUrl, InputStream inputStream, String fileName) throws AdapterException {
        String url = baseUrl + "/worker/file";
        logger.info("Trying to start save file on endpoint: " + url);


        HttpEntity httpEntity = new MultipartEntity();
        ((MultipartEntity) httpEntity).addPart("file_upload", new InputStreamBody(inputStream, fileName));

        try {
            String responseString = Request.Post(url)
                    .body(httpEntity)
                    .connectTimeout(1000)
                    .socketTimeout(100000)
                    .execute().returnContent().asString();

            logger.info("File saved successfully at worker");
            return responseString;
        } catch (IOException e) {
            e.printStackTrace();
            throw new AdapterException("Could not save file on endpoint " + url);
        }
    }

    public static InputStream getFileFromWorker(String baseUrl, String fileName) throws AdapterException {
        String url = baseUrl + "/worker/file/" + fileName;
        logger.info("Trying to get file from endpoint: " + url);

        try {
            InputStream inputStream = Request.Get(url)
                    .connectTimeout(1000)
                    .socketTimeout(100000)
                    .execute().returnContent().asStream();

            logger.info("Got File from worker successfully from worker");
            return inputStream;
        } catch (IOException e) {
            e.printStackTrace();
            throw new AdapterException("Could not get file from endpoint " + url);
        }
    }

    public static List<String> getAllFilePathsFromWorker(java.lang.String baseUrl) throws AdapterException {
        String url = baseUrl + "/worker/file/";
        logger.info("Trying to get file paths from endpoint: " + url);

        try {
            String stringResponse = Request.Get(url)
                    .connectTimeout(1000)
                    .socketTimeout(100000)
                    .execute().returnContent().asString();
            List<String> paths = new Gson().fromJson(stringResponse, List.class);

            logger.info("Got File paths successfully");
            return paths;
        } catch (IOException e) {
            e.printStackTrace();
            throw new AdapterException("Could not get file from endpoint " + url);
        }
    }

    public static void deleteFileFromWorker(String baseUrl, String fileName) throws AdapterException {
        String url = baseUrl + "/worker/file/";
        logger.info("Trying to delete file from endpoint: " + url);

        try {
            Request.Delete(url)
                    .connectTimeout(1000)
                    .socketTimeout(100000)
                    .execute();

            logger.info("Deleted File successfully");
        } catch (IOException e) {
            e.printStackTrace();
            throw new AdapterException("Could not delete file from endpoint " + url);
        }
    }

   private static AdapterDescription getAdapterDescriptionById(AdapterStorageImpl adapterStorage, String id) {
        AdapterDescription adapterDescription = null;
        List<AdapterDescription> allAdapters = adapterStorage.getAllAdapters();
        for (AdapterDescription a : allAdapters) {
            if (a.getUri().endsWith(id)) {
                adapterDescription = a;
            }
        }

        return adapterDescription;
    }

    private static String encodeValue(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException(ex.getCause());
        }
    }




}

