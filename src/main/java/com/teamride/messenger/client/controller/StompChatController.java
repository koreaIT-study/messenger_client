package com.teamride.messenger.client.controller;

import javax.annotation.Resource;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import ch.qos.logback.core.util.FileUtil;
import com.teamride.messenger.client.config.Constants;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.bind.annotation.*;

import com.teamride.messenger.client.config.KafkaConstants;
import com.teamride.messenger.client.dto.ChatMessageDTO;
import com.teamride.messenger.client.dto.ChatRoomDTO;
import com.teamride.messenger.client.repository.ChatRoomRepository;
import com.teamride.messenger.client.service.StompChatService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@EnableAsync
@RestController
@RequiredArgsConstructor
@Slf4j
public class StompChatController {

    @Resource(name = "stompChatService")
    private StompChatService stompChatService;

    private final ChatRoomRepository chatRoomRepository;

    private final KafkaTemplate<String, ChatMessageDTO> kafkaTemplate;

    private final HttpSession httpSession;

    @MessageMapping("/chat/input")
    public void chatInput(ChatMessageDTO messageDTO) {
        kafkaTemplate.send(KafkaConstants.CHAT_INPUT, messageDTO); // ?????? ?????? ???????????? ???, partition 100?????? RR(Round Robin)
    }

    @KafkaListener(topics = KafkaConstants.CHAT_INPUT, groupId = KafkaConstants.GROUP_ID)
    public void listenInput(ChatMessageDTO chatMessageDTO, Acknowledgment ack) {
        log.info("Received Msg chat-input {}", chatMessageDTO);

        try {
            stompChatService.sendChatInput(chatMessageDTO);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("error::{}", e);
        }
    }

    @MessageMapping(value = "/chat/message")
    public void message(ChatMessageDTO message) {
        log.info("::: StompChatController.message in :::" + message);
        // view?????? message ????????? ????????? ?????????
        // service ??????
        String partitionKey = message.getRoomId()
            .substring(0, 2);
        ListenableFuture<SendResult<String, ChatMessageDTO>> future = kafkaTemplate.send(KafkaConstants.CHAT_SERVER,
                partitionKey, message);

        future.addCallback((result) -> {
            int partition = result.getRecordMetadata()
                .partition();
            log.info("message ?????? ??????, message :: {}, partition num is {},  result is :: {}", message, partition, result);
        }, (ex) -> {
            log.error("message ?????? ??????, message :: {}, error is :: {}", message, ex);
        });
    }

    @KafkaListener(topics = KafkaConstants.CHAT_CLIENT, groupId = KafkaConstants.GROUP_ID)
    public void listen(ChatMessageDTO message, Acknowledgment ack) {
        log.info("Received Msg chat-client " + message);

        // message ??????
        // ??????????????? room id??? ???????????? ?????????
        // room id ????????? user id ?????? logic ?????? ??????
        try {
            stompChatService.sendMessage(message);
            log.info("message:::" + message);

            Mono<ChatRoomDTO> monoChatRoomDTO = chatRoomRepository.findRoomById(message.getRoomId());
            monoChatRoomDTO.subscribe(room -> {
                log.info("chatRoom DTO::" + room);
                stompChatService.sendMessageRoomList(room);
            });
            ack.acknowledge();
        } catch (Exception e) {
            log.error("error::{}", e);
        }
    }

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> fileUpload(@RequestPart(value = "files", required = false) List<MultipartFile> files, ChatMessageDTO msg){
        log.info("client server file receive ::::");
        MultiValueMap<String, Object> map = new LinkedMultiValueMap<>();
        map.add("msg",msg);
        files.forEach(file -> map.add("files", file.getResource()));
        log.info("?????????~~~");
        Integer successCnt = WebClient.builder()
                .baseUrl(Constants.FILE_SERVER_URL)
                .build()
                .post()
                .uri("/messege-file")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromMultipartData(map))
                .retrieve()
                .onStatus(HttpStatus::is4xxClientError, e -> Mono.error(new HttpClientErrorException(e.statusCode())))
                .onStatus(HttpStatus::is5xxServerError, e -> Mono.error(new HttpServerErrorException(e.statusCode())))
                .bodyToMono(Integer.class)
                .block();
        log.info("?????????~~~");
        return ResponseEntity.ok(successCnt);
    }

    @GetMapping(value = "/downFile/{roomId}/{msg}")
    public ResponseEntity<?> downFile(@PathVariable String roomId, @PathVariable String msg){
        log.info("emf");
        String uri = "/downFile/" + roomId + "/" + msg;
        String fileNmae = msg.substring(msg.lastIndexOf("||"));

        MultiValueMap<String, Object> rsp = WebClient.builder().baseUrl(Constants.FILE_SERVER_URL)
                .build()
                .get()
                .uri(uri)
                .accept(MediaType.MULTIPART_FORM_DATA)
                .retrieve()
                .onStatus(HttpStatus::is4xxClientError, e -> Mono.error(new HttpClientErrorException(e.statusCode())))
                .onStatus(HttpStatus::is5xxServerError, e -> Mono.error(new HttpServerErrorException(e.statusCode())))
                .bodyToMono(MultiValueMap.class)
                .block();

        org.springframework.core.io.Resource resource = ((MultipartFile)rsp.get("file")).getResource();

        HttpHeaders headers = new HttpHeaders();
        // ???????????? ????????? ????????? ???????????? ????????? ??????????????? ???????????? ??????
        headers.setContentDisposition(ContentDisposition.builder("attachment").filename(fileNmae).build());

        log.info(":::::: ?????? ??????????????? ?????????!! ::::::");
        return new ResponseEntity<Object>(resource, headers, HttpStatus.OK);
    }
}
