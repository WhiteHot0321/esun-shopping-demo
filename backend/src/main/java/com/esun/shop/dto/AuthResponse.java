package com.esun.shop.dto;

import com.esun.shop.model.Member;

public class AuthResponse {
    private String token;
    private String email;
    private Member.Role role;

    public AuthResponse() {
    }

    public AuthResponse(String token, String email) {
        this(token, email, Member.Role.BUYER);
    }

    public AuthResponse(String token, String email, Member.Role role) {
        this.token = token;
        this.email = email;
        this.role = role;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Member.Role getRole() {
        return role;
    }

    public void setRole(Member.Role role) {
        this.role = role;
    }
}
