package com.fcourt.pos.dto.response;

import com.fcourt.pos.api.PosViews;
import java.util.List;

/** Một trang sản phẩm cho owner (gồm cả sản phẩm đã ngừng bán). */
public record ProductPageResponse(List<PosViews.Product> items, int page, int size, long total) { }
