package com.tianji.promotion.domain.dto;

import lombok.Data;

@Data
public class UserCouponDTO {
	/**
	 * 用户id
	 */
	private Long userId;
	/**
	 * 优惠券id
	 */
	private Long couponId;
	/**
	 * 序列号
	 */
	private Long serialNum;
}
