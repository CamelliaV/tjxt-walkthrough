package com.tianji.api.client.promotion;

import com.tianji.api.client.promotion.fallback.PromotionClientFallback;
import com.tianji.api.dto.promotion.CouponDiscountDTO;
import com.tianji.api.dto.promotion.OrderCouponDTO;
import com.tianji.api.dto.promotion.OrderCourseDTO;
import io.swagger.annotations.ApiOperation;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author CamelliaV
 * @since 2024/12/2 / 14:31
 */
@FeignClient(name = "promotion-service", fallbackFactory = PromotionClientFallback.class)
public interface PromotionClient {
	@ApiOperation("查询可用优惠方案优惠详情")
	@PostMapping("/user-coupons/available")
	List<CouponDiscountDTO> findDiscountSolution(@RequestBody List<OrderCourseDTO> orderCourseDTOList);

	@ApiOperation("根据券方案计算订单优惠明细")
	@PostMapping("/user-coupons/discount")
	CouponDiscountDTO queryDiscountDetailByOrder(@RequestBody OrderCouponDTO orderCouponDTO);

	@ApiOperation("核销指定优惠券")
	@PutMapping("/user-coupons/use")
	void writeOffCoupon(@RequestParam("couponIds") List<Long> userCouponIds);

	@ApiOperation("退还指定优惠券")
	@PutMapping("/user-coupons/refund")
	void refundCoupon(@RequestParam("couponIds") List<Long> userCouponIds);

	@ApiOperation("根据优惠劵id集合查询优惠券规则集合")
	@GetMapping("/user-coupons/rules")
	List<String> queryDiscountRules(@RequestParam("couponIds") List<Long> userCouponIds);

}
