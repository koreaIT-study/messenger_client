package com.teamride.messenger.client.controller;

import java.io.IOException;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.ModelAndView;

import com.teamride.messenger.client.config.ClientConfig;
import com.teamride.messenger.client.config.Constants;
import com.teamride.messenger.client.config.WebClientConfig;
import com.teamride.messenger.client.dto.UserDTO;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@RestController
@Slf4j
public class KakaoLoginController {
    private static final String REST_API_KEY = ClientConfig.getClientConfigInstance()
        .getKakao()
        .getRestApiKey();
    private static final String REDIRECT_URI = ClientConfig.getClientConfigInstance()
        .getKakao()
        .getRedirectUrl();

    @Autowired
    HttpSession httpSession;

    @Autowired
    private WebClientConfig webClient;

    @Autowired
    HttpServletResponse httpServletResponse;

    @GetMapping("/kakao_login")
    public void kakaoLogin() {
        StringBuilder url = new StringBuilder();
        url.append("https://kauth.kakao.com/oauth/authorize?");
        url.append("client_id=" + REST_API_KEY);
        url.append("&redirect_uri=" + REDIRECT_URI);
        url.append("&response_type=code");

        try {
            httpServletResponse.sendRedirect(url.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @RequestMapping(value = "/oauth", produces = "application/json", method = { RequestMethod.GET, RequestMethod.POST })
    public void kakaoLogin(@RequestParam("code") String code, HttpServletResponse resp) throws IOException {
        log.info("kakao authorize code is :: " + code);
        try {
            String accessToken = getKakaoAccessToken(code);
            httpSession.setAttribute("access_token", accessToken); // ??????????????? ??? ????????????

            getKakaoUserInfo(accessToken); // ????????? ??????
            resp.sendRedirect("friend");
        } catch (Exception e) {
            log.error("kakao login error :: ", e);
            // TODO login??? error param????????? ???????????? popup ??????
            // ?????? error??? ????????? ??????
            resp.sendRedirect("login");
        }
    }

    public String getKakaoAccessToken(String code) {
        // ???????????? ?????? api
        WebClient webClientAuth = WebClient.builder()
            .baseUrl("https://kauth.kakao.com")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

        // ????????? ????????? ?????? ????????? & ?????? ??????
        JSONObject response = webClientAuth.post()
            .uri(uriBuilder -> uriBuilder.path("/oauth/token")
                .queryParam("grant_type", "authorization_code")
                .queryParam("client_id", REST_API_KEY)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("code", code)
                .build())
            .retrieve()
            .bodyToMono(JSONObject.class)
            .block();

        log.info("response::" + response);
        return (String) response.get("access_token");
    }

    private JSONObject getKakaoUserInfo(String accessToken) throws IOException {
        JSONObject response = getKakaoApiWebClient().post()
            .uri(uriBuilder -> uriBuilder.path("/v2/user/me")
                .build())
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .bodyToMono(JSONObject.class)
            .block();

        log.info("response::" + response);
        // ?????? ???????????? ????????? ?????? ???????????? (????????? ?????? ?????? ??????????????? ????????? ?????????)
        Map<String, Object> map = (Map<String, Object>) response.get("kakao_account");
        Map<String, Object> profile = (Map<String, Object>) map.get("profile");
        String name = (String) profile.get("nickname");
        String email = (String) map.get("email");

        log.info("name::" + name);
        log.info("email::" + email);

        // ??????????????? email, nickname ?????? ??? ????????? ????????? insert?????? ????????? ??????
        UserDTO tempUserDTO = UserDTO.builder()
            .email(email)
            .name(name)
            .build();
        UserDTO userDTO = webClient.getWebClient()
            .post()
            .uri("/social_login")
            .bodyValue(tempUserDTO)
            .retrieve()
            .bodyToMono(UserDTO.class)
            .block();
        
        httpSession.setAttribute(Constants.LOGIN_SESSION, userDTO.getId());
        httpSession.setAttribute(Constants.LOGIN_SESSION_NAME, userDTO.getName());
        return response;
    }

    @GetMapping(value = "/logout")
    public ModelAndView kakaoLogout(HttpSession session) {
        ModelAndView mv = new ModelAndView("sign_in");
        // String accessToken = (String) session.getAttribute("access_token");
        //
        // JSONObject response = getKakaoApiWebClient().post()
        // .uri(uriBuilder -> uriBuilder.path("/v1/user/logout").build())
        // .header("Authorization", "Bearer " + accessToken)
        // .retrieve().bodyToMono(JSONObject.class).block();
        // log.info("logout response::"+response);
        // // ????????????????????? ????????? ????????? ???????????? ??????
        // session.removeAttribute("access_token");
        session.removeAttribute("userEmail");
        session.removeAttribute("name");
        return mv;
    }

    @GetMapping("/unlink")
    public void kakaoUnlink() {
        JSONObject response = getKakaoApiWebClient().post()
            .uri(uriBuilder -> uriBuilder.path("/v1/user/logout")
                .build())
            .header("Authorization", "Bearer " + httpSession.getAttribute("access_token"))
            .retrieve()
            .bodyToMono(JSONObject.class)
            .block();
        log.info("unlink response::" + response);
    }

    @GetMapping("web-client-test")
    public Mono<String> test() {
        return webClient.getWebClient()
            .mutate()
            .baseUrl("http://localhost:12000")
            .build()
            .get()
            .uri("/test")
            .retrieve()
            .bodyToMono(String.class);

    }

    public WebClient getKakaoApiWebClient() {
        // ???????????? ?????? ?????????
        return WebClient.builder()
            .baseUrl("https://kapi.kakao.com")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

}
