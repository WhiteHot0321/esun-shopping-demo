package com.esun.shop.dto;

import java.util.List;

public class SupportAnswer {
    private String answer;
    private List<SourceInfo> sources;

    public SupportAnswer() {
    }

    public SupportAnswer(String answer, List<SourceInfo> sources) {
        this.answer = answer;
        this.sources = sources;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<SourceInfo> getSources() {
        return sources;
    }

    public void setSources(List<SourceInfo> sources) {
        this.sources = sources;
    }
}
