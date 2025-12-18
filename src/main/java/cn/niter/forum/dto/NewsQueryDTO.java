package cn.niter.forum.dto;

import lombok.Data;

@Data
public class NewsQueryDTO {
    private String search;
    private String tag;
    private String sort;
    private Long time;
    private Integer offset;
    private Integer size;
    private Integer column2;
}
