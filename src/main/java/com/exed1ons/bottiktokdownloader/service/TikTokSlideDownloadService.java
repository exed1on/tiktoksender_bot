package com.exed1ons.bottiktokdownloader.service;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class TikTokSlideDownloadService {

    private static final Logger logger = LoggerFactory.getLogger(TikTokSlideDownloadService.class);

    @Value("${download.directory.downloads}")
    private String downloadDirectory;

    public List<String> downloadSlides(String tiktokUrl) {
        logger.info("Starting downloadSlides with URL: " + tiktokUrl);
        List<String> downloadedPhotos = new ArrayList<>();
        try {
            String jsonResponse = sendPostRequest(tiktokUrl);
            if (jsonResponse != null) {
                logger.debug("Received JSON response: " + jsonResponse);

                Document doc = Jsoup.parse(jsonResponse);
                Elements downloadLinks = doc.select("img[src]");

                logger.info("Found " + downloadLinks.size() + " image links in the response");

                for (Element link : downloadLinks) {
                    String imageUrl = link.attr("src");
                    logger.debug("Processing link: " + imageUrl);

                    if (imageUrl.contains("tiktokcdn")) {
                        String downloadedPath = downloadImage(imageUrl);
                        if (downloadedPath != null) {
                            logger.info("Downloaded photo to: " + downloadedPath);
                            downloadedPhotos.add(downloadedPath);
                        } else {
                            logger.warn("Failed to download image: " + imageUrl);
                        }
                    }
                }
            } else {
                logger.warn("Received null response for URL: " + tiktokUrl);
            }
        } catch (Exception e) {
            logger.error("Error downloading slides: " + e.getMessage(), e);
        }
        logger.info("Finished downloadSlides with " + downloadedPhotos.size() + " photos downloaded.");
        return downloadedPhotos;
    }

    public String downloadAudio(String tiktokUrl) {
        logger.info("Starting downloadAudio with URL: " + tiktokUrl);
        try {
            String jsonResponse = sendPostRequest(tiktokUrl);
            if (jsonResponse != null) {
                logger.debug("Received JSON response for audio: " + jsonResponse);

                Document doc = Jsoup.parse(jsonResponse);
                Elements audioLinks = doc.select("a[href*='download?token=']");

                logger.info("Found " + audioLinks.size() + " audio links in the response");

                Element link = audioLinks.last();

                if(link == null) {
                    logger.warn("No audio links found in the response");
                    return null;
                }

                String audioUrl = link.attr("href");
                logger.debug("Processing audio link: " + audioUrl);

                if (audioUrl.contains("tiktokio")) {
                    String downloadedPath = downloadAudio(audioUrl, "mp3");
                    if (downloadedPath != null) {
                        logger.info("Downloaded audio to: " + downloadedPath);
                        return downloadedPath;
                    } else {
                        logger.warn("Failed to download audio: " + audioUrl);
                    }
                }
            } else {
                logger.warn("Received null response for URL: " + tiktokUrl);
            }
        } catch (Exception e) {
            logger.error("Error downloading audio: " + e.getMessage(), e);
        }
        return null;
    }

    private String sendPostRequest(String tiktokUrl) {
        logger.info("Sending POST request to TikTok API with URL: " + tiktokUrl);
        String apiUrl = "https://tikio.com/api/v1/tk/html";
        String prefix = "tikio.com";

        String jsonBody = new JSONObject()
                .put("vid", tiktokUrl)
                .put("prefix", prefix)
                .toString();

        try {
            HttpURLConnection conn = getHttpURLConnection(apiUrl, jsonBody);

            int responseCode = conn.getResponseCode();
            logger.info("POST request response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = conn.getInputStream();
                String response = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                logger.debug("Received response from TikTok API: " + response);

                logger.info("Response: " + response);
                return response;
            } else {
                logger.error("Failed to get a valid response. HTTP Code: " + responseCode);
                return null;
            }

        } catch (Exception e) {
            logger.error("Error sending POST request: " + e.getMessage(), e);
            return null;
        }
    }

    private static HttpURLConnection getHttpURLConnection(String apiUrl, String jsonBody) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        conn.setRequestProperty("Referer", "https://tikio.com/");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
        return conn;
    }

    private String downloadImage(String imageUrl) {
        logger.info("Starting download for image: " + imageUrl);
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int responseCode = conn.getResponseCode();
            logger.info("GET request response code: " + responseCode);
            logger.info(conn.getResponseMessage());

            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (InputStream inputStream = conn.getInputStream()) {
                    BufferedImage image = ImageIO.read(inputStream);

                    if (image != null) {
                        String fileName = imageUrl.substring(imageUrl.lastIndexOf("/") + 1, imageUrl.indexOf("~"));
                        String fileExtension = "jpg";
                        File downloadDir = new File(downloadDirectory);

                        if (!downloadDir.exists()) {
                            downloadDir.mkdirs();
                            logger.info("Created download directory: " + downloadDir.getAbsolutePath());
                        }

                        File outputFile = new File(downloadDir, fileName + "." + fileExtension);
                        logger.info("Saving image to file: " + outputFile.getAbsolutePath());

                        ImageIO.write(image, fileExtension, outputFile);
                        logger.info("Image downloaded successfully: " + outputFile.getName());
                        return outputFile.getAbsolutePath();
                    } else {
                        logger.error("Failed to read image from input stream.");
                    }
                }
            } else {
                logger.error("Failed to download image. HTTP response code: " + responseCode);
            }

        } catch (Exception e) {
            logger.error("Error downloading image: " + e.getMessage(), e);
        }
        return null;
    }

    private String downloadAudio(String fileUrl, String fileExtension) {
        logger.info("Starting download for file: " + fileUrl);
        try {
            URL url = new URL(fileUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int responseCode = conn.getResponseCode();
            logger.info("GET request response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (InputStream inputStream = conn.getInputStream()) {
                    byte[] fileData = inputStream.readAllBytes();

                    String fileName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1, fileUrl.indexOf("?"));
                    File downloadDir = new File(downloadDirectory);

                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs();
                        logger.info("Created download directory: " + downloadDir.getAbsolutePath());
                    }

                    File outputFile = new File(downloadDir, fileName + "." + fileExtension);
                    logger.info("Saving file to: " + outputFile.getAbsolutePath());

                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        fos.write(fileData);
                    }

                    logger.info("File downloaded successfully: " + outputFile.getName());
                    return outputFile.getAbsolutePath();
                }
            } else {
                logger.error("Failed to download file. HTTP response code: " + responseCode);
            }

        } catch (Exception e) {
            logger.error("Error downloading file: " + e.getMessage(), e);
        }
        return null;
    }

    public void deleteFile(String fileName) {
        String filePath = downloadDirectory + File.separator + fileName;
        File fileToDelete = new File(filePath);
        if (fileToDelete.exists()) {
            if (fileToDelete.delete()) {
                logger.info("File deleted successfully: " + filePath);
            } else {
                logger.info("Unable to delete file: " + filePath);
            }
        }
    }
}