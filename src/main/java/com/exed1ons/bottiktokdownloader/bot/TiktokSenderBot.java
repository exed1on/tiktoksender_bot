package com.exed1ons.bottiktokdownloader.bot;

import com.exed1ons.bottiktokdownloader.service.ImageToGifConverter;
import com.exed1ons.bottiktokdownloader.service.SendReelService;
import com.exed1ons.bottiktokdownloader.service.SendVideoService;
import com.exed1ons.bottiktokdownloader.service.TikTokLinkConverter;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Setter
@Getter
@Component
public class TiktokSenderBot extends TelegramLongPollingBot {

    private String botName;
    private String botToken;

    private final SendVideoService sendVideoService;
    private final TikTokLinkConverter tikTokLinkConverter;
    private final SendReelService sendReelService;
    private final ImageToGifConverter imageToGifConverter;

    private static final Logger logger = LoggerFactory.getLogger(TiktokSenderBot.class);

    public TiktokSenderBot(@Value("${bot.username}") String botName, @Value("${bot.token}") String botToken, SendVideoService sendVideoService, TikTokLinkConverter tikTokLinkConverter, SendReelService sendReelService, ImageToGifConverter imageToGifConverter) {

        super(botToken);
        this.botName = botName;
        this.botToken = botToken;
        this.sendVideoService = sendVideoService;
        this.tikTokLinkConverter = tikTokLinkConverter;
        this.sendReelService = sendReelService;
        this.imageToGifConverter = imageToGifConverter;
    }

    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            Message message = update.getMessage();

            logger.info("Received message: " + message.getText());
            logger.info("From: " + message.getChatId());

            if (message.hasText() && message.getText().equals("/gif") && message.isReply()) {
                Message repliedMessage = message.getReplyToMessage();
                if (repliedMessage.hasPhoto()) {
                    PhotoSize photo = repliedMessage.getPhoto().get(repliedMessage.getPhoto().size() - 1);
                    sendGif(message.getChatId().toString(),
                            imageToGifConverter.createGifFromImage(
                                    downloadImage(photo.getFileId())));
                } else {
                    sendMessage(message.getChatId().toString(),
                            "/gif command should be used with a photo reply only");
                }
            } else {
                processMessage(message);
            }
        }
    }

    private void processMessage(Message message) {
        if (message.hasText()) {
            String text = message.getText();
            String link = null;

            Pattern shortUrlPattern = Pattern.compile("https://vm.tiktok.com/[A-Za-z0-9]+");
            Matcher shortUrlMatcher = shortUrlPattern.matcher(text);
            if (shortUrlMatcher.find()) {
                link = shortUrlMatcher.group();

                try {
                    link = tikTokLinkConverter.expandUrlUsingApi(link);
                } catch (IOException e) {
                    logger.error("Failed to resolve short URL: " + link);
                }
            }

            Pattern longUrlPattern = Pattern.compile("https://www.tiktok.com/@[^/]+/video/[0-9]+");
            Matcher longUrlMatcher = longUrlPattern.matcher(text);
            if (longUrlMatcher.find()) {
                link = longUrlMatcher.group();
            }

            Pattern instagramReelPattern = Pattern.compile("https://www.instagram.com/reel/[A-Za-z0-9-_]+");
            Matcher instagramReelMatcher = instagramReelPattern.matcher(text);
            if (instagramReelMatcher.find()) {
                link = instagramReelMatcher.group();
            }

            if (link != null) {
                if (link.contains("tiktok.com")) {
                    String videoId = sendVideoService.extractVideoId(link);
                    if (videoId != null) {
                        sendTikTokVideo(message.getChatId().toString(), sendVideoService.getVideo(link));
                    } else {
                        logger.error("Failed to extract video ID from link: " + link);
                    }
                } else if (link.contains("instagram.com/reel")) {
                    InputFile reelVideo = sendReelService.getVideo(link);
                    if (reelVideo != null) {
                        sendReelVideo(message.getChatId().toString(), reelVideo);
                    } else {
                        logger.error("Failed to download Instagram Reel from link: " + link);
                    }
                }
            } else {
                logger.warn("No valid TikTok or Instagram URL found in message: " + text);
            }
        }
    }

    public BufferedImage downloadImage(String fileId) {
        try {
            File telegramFile = execute(new GetFile(fileId));
            String filePath = telegramFile.getFilePath();

            String fileUrl = "https://api.telegram.org/file/bot" + getBotToken() + "/" + filePath;

            return ImageIO.read(new URL(fileUrl));
        } catch (TelegramApiException | IOException e) {
            logger.error("Failed to download image from Telegram: ", e);
            return null;
        }
    }

    public void sendMessage(String chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Error while sending message", e);
        }
    }

    public void sendAudio(String chatId, InputFile audioFile) {
        SendAudio message = new SendAudio();
        message.setChatId(chatId);
        message.setAudio(audioFile);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Error while sending message", e);
        }
    }

    public String sendVideo(String chatId, InputFile videoFile) {
        SendVideo message = new SendVideo();
        message.setChatId(chatId);
        message.setVideo(videoFile);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Error while sending message", e);
        }

        return videoFile.getMediaName();
    }

    public void sendTikTokVideo(String chatId, InputFile videoFile) {
        String fileName = sendVideo(chatId, videoFile);

        try {
            logger.info("Deleting video file: " + fileName);
            if (fileName != null) {
                sendVideoService.deleteVideoFile(fileName);
            }
        } catch (Exception e) {
            logger.error("Error while deleting video file", e);
        }
    }

    public void sendReelVideo(String chatId, InputFile videoFile) {
        String fileName = sendVideo(chatId, videoFile);

        try {
            logger.info("Deleting video file: " + fileName);
            if (fileName != null) {
                sendReelService.deleteVideoFile(fileName);
            }
        } catch (Exception e) {
            logger.error("Error while deleting video file", e);
        }
    }

    public void sendGif(String chatId, InputFile gifFile) {
        SendAnimation message = new SendAnimation();
        message.setChatId(chatId);
        message.setAnimation(gifFile);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Error while sending GIF", e);
        }
    }
}
