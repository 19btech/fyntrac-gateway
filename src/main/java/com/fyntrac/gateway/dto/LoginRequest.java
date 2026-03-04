package com.fyntrac.gateway.dto;

public class LoginRequest {
    private String email;
    private String pswd;

    public LoginRequest() {
    }

    public LoginRequest(String email, String pswd) {
        this.email = email;
        this.pswd = pswd;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPswd() {
        return pswd;
    }

    public void setPswd(String pswd) {
        this.pswd = pswd;
    }
}
