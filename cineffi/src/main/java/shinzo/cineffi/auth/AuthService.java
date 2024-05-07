package shinzo.cineffi.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import shinzo.cineffi.domain.dto.*;
import shinzo.cineffi.domain.entity.user.User;
import shinzo.cineffi.domain.entity.user.UserAccount;
import shinzo.cineffi.domain.entity.user.UserAnalysis;
import shinzo.cineffi.jwt.JWTUtil;
import shinzo.cineffi.jwt.JWToken;
import shinzo.cineffi.domain.entity.user.UserActivityNum;
import shinzo.cineffi.user.repository.UserAccountRepository;
import shinzo.cineffi.user.repository.UserActivityNumRepository;
import shinzo.cineffi.user.repository.UserAnalysisRepository;
import shinzo.cineffi.user.repository.UserRepository;

import java.util.Optional;
import java.util.Random;

import static shinzo.cineffi.jwt.JWTUtil.ACCESS_PERIOD;
import static shinzo.cineffi.jwt.JWTUtil.REFRESH_PERIOD;

@Service
@Transactional
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountRepository userAccountRepository;
    private final UserRepository userRepository;
    private final UserActivityNumRepository userActivityNumRepository;
    private final UserAnalysisRepository userAnalysisRepository;
    @Value("${kakao.rest_api_key}")
    private String restApiKey;
    @Value("${kakao.redirect_url}")
    private String redirectUrl;
    private EmailRequestDTO request;

    //필요하다면 카카오 회원가입, 유저아이디 반환
    public Long loginByKakao(String accessToken){
        String email = requestKakaoEmail(accessToken);
        boolean isEmailDuplicate = userAccountRepository.existsByEmail(email);

        //회원가입 전적 없으면 회원가입
        if(!isEmailDuplicate) {
            AuthRequestDTO dto = AuthRequestDTO.builder()
                    .email(email)
                    .nickname(generateNickname())
                    .isauthentication(true) //카카오는 이미 겅즘된 이메일이므로 그냥 true
                    .build();
            joinUser(dto);
        }

        return getUserIdByEmail(email);
    }

    public KakaoToken requestKakaoToken(String code){
        RestTemplate rt = new RestTemplate();
        //요청보낼 헤더 생성
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        //요청보낼 바디 생성
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", restApiKey);
        params.add("redirect_uri", redirectUrl);
        params.add("code", code);
        //헤더 바디 합치기
        HttpEntity<MultiValueMap<String, String>> kakaoTokenRequest =
                new HttpEntity<>(params, headers);

        //ResponseEntity 객체를 String 형만 받도록 생성. 응답받는 값이 Json 형식이니까
        ResponseEntity<String> accessTokenResponse = rt.exchange(
                "https://kauth.kakao.com/oauth/token",
                HttpMethod.POST,
                kakaoTokenRequest,
                String.class
        );

        //String으로 받은 Json 형식의 데이터를 ObjectMapper 라는 클래스를 사용해 객체로 변환
        ObjectMapper objectMapper = new ObjectMapper();
        KakaoToken kakaoToken = null;
        try {
            kakaoToken = objectMapper.readValue(accessTokenResponse.getBody(), KakaoToken.class);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return kakaoToken;

    }

    //카카오 토큰으로 카카오 이메일 가져오기
    private String requestKakaoEmail(String accessToken){
        RestTemplate rt = new RestTemplate();

        //헤더 생성
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        HttpEntity<MultiValueMap<String, String>> kakaoProfileRequest = new HttpEntity<>(headers);

        //요청을 만들어 보내고, 그걸 응답에 받기
        ResponseEntity<String> kakaoProfileResponse = rt.exchange(
                "https://kapi.kakao.com/v2/user/me",
                HttpMethod.POST,
                kakaoProfileRequest,
                String.class
        );

        ObjectMapper objectMapper = new ObjectMapper();
        KakaoProfile kakaoProfile = null;
        try {
            kakaoProfile = objectMapper.readValue(kakaoProfileResponse.getBody(), KakaoProfile.class);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        return kakaoProfile.getKakaoAccount().getEmail();
    }
    //랜덤 비중복 닉네임 생성기
    public String generateNickname(){
        boolean isDup = true;
        String newNickname = "";
        while(isDup){
            Random random = new Random();
            int randomNum = random.nextInt(100000);
            String randomStr = "";
            switch(randomNum%4){
                case 0:
                    randomStr = "강아지";
                    break;
                case 1:
                    randomStr = "고양이";
                    break;
                case 2:
                    randomStr = "앵무새";
                    break;
                case 3:
                    randomStr = "토끼";
                    break;
            }

            newNickname = randomStr + randomNum;
            isDup = userRepository.existsByNickname(newNickname);
        }
        User user = User.builder().nickname(newNickname).build();
        return newNickname;
    }

    public boolean authUser(AuthRequestDTO request) {
        boolean isEmailDuplicate = userAccountRepository.existsByEmail(request.getEmail());
        boolean isNickNameDuplicate = userRepository.existsByNickname(request.getNickname());
        if (isEmailDuplicate) {
            return false; // 이메일 중복 시 false 반환
        }
        if(isNickNameDuplicate){
            return false; // 이메일 중복 시 false 반환
        }
        joinUser(request);

        return true; //이메일 중복 없을 때는 true
    }

    public boolean emailLogin(LoginRequestDTO request) {
        UserAccount userAccount = userAccountRepository.findByEmail(request.getEmail());
        return BCrypt.checkpw(request.getPassword(), userAccount.getPassword());
    }

    public Long getUserIdByEmail(String email) {
        Optional<UserAccount> user = Optional.ofNullable(userAccountRepository.findByEmail(email));
        return user.map(UserAccount::getId).orElse(null);
    }

     static public Long getLoginUserId(Object principal) {
        Long loginUserId = null;
        if(principal != "anonymousUser")
            loginUserId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getPrincipal().toString());
        return loginUserId;

    };

    public void normalLoginRefreshToken(Long memberNo, String refreshToken) {
        UserAccount userAccount = userAccountRepository.getReferenceById(memberNo);//userAccount객체
        userAccount.setUserToken(refreshToken);
        userAccountRepository.save(userAccount);


    }

    public Object[] makeCookie(Long userId){
        JWToken jwToken = JWTUtil.allocateToken(userId,"ROLE_USER");//액세스 토큰 발급
        //Access 토큰 쿠키
        ResponseCookie accessCookie = ResponseCookie.from("access",jwToken.getAccessToken())
                .sameSite("None")
                .maxAge(ACCESS_PERIOD)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .build();
        //Refresh 토큰 쿠키
        normalLoginRefreshToken(userId, jwToken.getRefreshToken());
        ResponseCookie refreshCookie = ResponseCookie.from("refresh",jwToken.getRefreshToken())
                .sameSite("None")
                .maxAge(REFRESH_PERIOD)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, String.valueOf(accessCookie));
        headers.add(HttpHeaders.SET_COOKIE, String.valueOf(refreshCookie));

        return new Object[] {headers};
    }

    public LoginResponseDTO userInfo(Long userId){
        Optional<User> user = userRepository.findById(userId);
        String nick = user.map(User::getNickname).orElse("NOt user");
        Integer level = user.map(User::getLevel).orElse(1);
        byte[] profileImage = user.map(User::getProfileImage).orElse("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".getBytes());
        Boolean isBad = user.map(User::getIsBad).orElse(false);
        Boolean isCertified = user.map(User::getIsCertified).orElse(false);

        return LoginResponseDTO.builder()
                .userId(userId)
                .nickname(nick)
                .level(level)
                .profileImage(profileImage)
                .isBad(isBad)
                .isCertified(isCertified)
                .build();
    }
    @Transactional
    private void joinUser(AuthRequestDTO request){
        User user = User.builder()
                .nickname(request.getNickname())
                .build();
        userRepository.save(user);

        UserAccount userAccount = null;
        if(request.getPassword() != null) {
            userAccount = UserAccount.builder()
                    .password(BCrypt.hashpw(request.getPassword(), BCrypt.gensalt()))
                    .email(request.getEmail())
                    .user(user)
                    .isAuthentication(request.getIsauthentication())
                    .build();
        }
        else{
            userAccount = UserAccount.builder()
                    .email(request.getEmail())
                    .user(user)
                    .isAuthentication(request.getIsauthentication())
                    .build();
        }

        userAccountRepository.save(userAccount);
        UserActivityNum userActivityNum = UserActivityNum.builder()
                .user(user)
                .build();
        userActivityNumRepository.save(userActivityNum);
        userAnalysisRepository.save(UserAnalysis.builder().user(user).build());
    }

    public boolean dupMail(EmailRequestDTO request) {
        boolean isdup = userAccountRepository.existsByEmail(request.getEmail());

        return isdup;
    }

    public boolean dupNickname(NickNameDTO request) {
        boolean isdup = userRepository.existsByNickname(request.getNickname());

        return isdup;
    }

    public Object[] logout(Long userId) {

        HttpHeaders headers = new HttpHeaders();
        ResponseCookie cookie = ResponseCookie.from("access", "")
                .maxAge(0)
                .path("/")
                .httpOnly(false)
                .build();

        ResponseCookie cookie2 = ResponseCookie.from("refresh", "")
                .maxAge(0)
                .path("/")
                .httpOnly(false)
                .build();

        headers.add(HttpHeaders.SET_COOKIE, cookie.toString());
        headers.add(HttpHeaders.SET_COOKIE, cookie2.toString());
        return new Object[] {headers};
    }
}
