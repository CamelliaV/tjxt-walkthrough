package com.tianji.promotion.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponDiscountDTO;
import com.tianji.promotion.domain.dto.OrderCouponDTO;
import com.tianji.promotion.domain.dto.OrderCourseDTO;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.service.IDiscountService;
import com.tianji.promotion.service.IUserCouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 前端控制器
 * </p>
 *
 * @author CamelliaV
 * @since 2024-11-26
 */
@RestController
@RequestMapping("/user-coupons")
@Api(tags = "用户卷相关接口")
@RequiredArgsConstructor
public class UserCouponController {
	private final IUserCouponService userCouponService;
	private final IDiscountService discountService;

	@ApiOperation("查询可用优惠方案优惠详情")
	@PostMapping("/available")
	public List<CouponDiscountDTO> findDiscountSolution(@RequestBody List<OrderCourseDTO> orderCourseDTOList) {
		return discountService.findDiscountSolution(orderCourseDTOList);
	}

	@ApiOperation("查询指定方案优惠详情")
	@PostMapping("/discount")
	public CouponDiscountDTO queryDiscountDetailByOrder(@RequestBody OrderCouponDTO dto) {
		return discountService.queryDiscountDetailByOrder(dto);
	}

	@ApiOperation("核销指定优惠券")
	@PutMapping("/use")
	public void writeOffCoupon(@RequestParam("couponIds") List<Long> userCouponIds) {
		userCouponService.writeOffCoupon(userCouponIds);
	}

	@ApiOperation("退还指定优惠券")
	@PutMapping("/refund")
	public void refundCoupon(@RequestParam("couponIds") List<Long> userCouponIds) {
		userCouponService.refundCoupon(userCouponIds);
	}

	@ApiOperation("领取优惠劵")
	@PostMapping("/{id}/receive")
	public void receiveCoupon(@PathVariable("id") Long id) {
		// userCouponService.receiveCoupon(id);
//		userCouponService.receiveCouponImplWithLua(id);
		userCouponService.receiveCouponImplWithAnnotation(id);
	}

	@ApiOperation("兑换优惠劵")
	@PostMapping("/{code}/exchange")
	public void exchangeCoupon(@PathVariable("code") String code) {
		// userCouponService.exchangeCoupon(code);
//		userCouponService.exchangeCouponWithLua(code);
		userCouponService.exchangeCouponWithAnnotation(code);
	}

	@ApiOperation("分页查询我的优惠劵")
	@GetMapping("/page")
	public PageDTO<CouponVO> queryMyCoupon(UserCouponQuery query) {
		return userCouponService.queryMyCoupon(query);
	}

	@ApiOperation("根据优惠劵id集合查询优惠券规则集合")
	@GetMapping("/rules")
	public List<String> queryDiscountRules(@RequestParam("couponIds") List<Long> userCouponIds) {
		return userCouponService.queryDiscountRules(userCouponIds);
	}
}
