package com.yas.cart.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConstantsTest {

    @Test
    void errorCodeConstants_ShouldExposeCartErrorCodes() {
        assertThat(Constants.ErrorCode.NOT_FOUND_PRODUCT).isEqualTo("NOT_FOUND_PRODUCT");
        assertThat(Constants.ErrorCode.NOT_EXISTING_ITEM_IN_CART).isEqualTo("NOT_EXISTING_ITEM_IN_CART");
        assertThat(Constants.ErrorCode.ADD_CART_ITEM_FAILED).isEqualTo("ADD_CART_ITEM_FAILED");
    }
}
