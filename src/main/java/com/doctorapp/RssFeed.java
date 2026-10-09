package com.doctorapp;

public class RssFeed {
    public int id;
    public String speciality;
    public String feedUrl;

    public RssFeed(int id, String speciality, String feedUrl) {
        this.id = id;
        this.speciality = speciality;
        this.feedUrl = feedUrl;
    }
}