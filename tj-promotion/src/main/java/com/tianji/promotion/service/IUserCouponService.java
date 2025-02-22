package com.tianji.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务类
 * </p>
 *
 * @author CamelliaV
 * @since 2024-11-26
 */
public interface IUserCouponService extends IService<UserCoupon> {

	void cacheCouponInfoWithLua(Coupon coupon);

	void receiveCoupon(Long id);

	void exchangeCouponWithLua(String code);

	void exchangeCoupon(String code);

	void checkAndCreateUserCoupon(Coupon coupon, Long userId);

	PageDTO<CouponVO> queryMyCoupon(UserCouponQuery query);

	void checkAndCreateUserCouponWithCode(Coupon coupon, Long userId, Long serialNum);

	void receiveCouponImplWithLua(Long id);

	void writeOffCoupon(List<Long> userCouponIds);

	void refundCoupon(List<Long> userCouponIds);

	List<String> queryDiscountRules(List<Long> userCouponIds);

	void receiveCouponImplWithAnnotation(Long id);

	void exchangeCouponWithAnnotation(String code);
}
