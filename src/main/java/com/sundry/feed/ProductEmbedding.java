package com.sundry.feed;

/**
 * product_embedding 表行对象。vector 是 JSON 数组字符串。
 */
public class ProductEmbedding {

    private Long productId;
    private String model;
    private Integer dim;
    private String vector;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getDim() {
        return dim;
    }

    public void setDim(Integer dim) {
        this.dim = dim;
    }

    public String getVector() {
        return vector;
    }

    public void setVector(String vector) {
        this.vector = vector;
    }
}
