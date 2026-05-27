package com.owldrive.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicSignupController {
    private final SignupApprovalService signupApprovalService;

    public PublicSignupController(SignupApprovalService signupApprovalService) {
        this.signupApprovalService = signupApprovalService;
    }

    @PostMapping("/signup-requests")
    SignupRequestStatusRecord submit(@RequestBody SignupRequestSubmission submission, HttpServletRequest request) {
        return signupApprovalService.submit(submission, clientIp(request), request.getHeader("User-Agent"));
    }

    @GetMapping("/signup-approvals/{token}")
    SignupRequestStatusRecord tokenInfo(@PathVariable("token") String token) {
        return signupApprovalService.tokenInfo(token);
    }

    @PostMapping("/signup-approvals/{token}/approve")
    SignupRequestStatusRecord approve(@PathVariable("token") String token) {
        return signupApprovalService.approve(token);
    }

    @PostMapping("/signup-approvals/{token}/reject")
    SignupRequestStatusRecord reject(@PathVariable("token") String token, @RequestBody(required = false) RejectSignupRequest request) {
        return signupApprovalService.reject(token, request);
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
