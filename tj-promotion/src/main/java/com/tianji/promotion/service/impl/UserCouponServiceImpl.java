package com.tianji.promotion.service.impl;

import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.constants.PromotionLuaConstants;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.ICouponService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.strategy.discount.DiscountStrategy;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
 * </p>
 *
 * @author CamelliaV
 * @since 2024-11-26
 */
@Service
@RequiredArgsConstructor
public class UserCouponServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {
	// * 必加resultType
	private static final RedisScript<Long> RECEIVE_COUPON_SCRIPT = RedisScript.of(new ClassPathResource("lua" +
			"/receive_coupon.lua"), Long.class);
	private static final RedisScript<Long> WRITE_COUPON_SCRIPT = RedisScript.of(new ClassPathResource("lua" +
			"/write_coupon.lua"), Long.class);
	private static final RedisScript<Long> EXCHANGE_COUPON_SCRIPT = RedisScript.of(new ClassPathResource("lua" +
			"/exchange_coupon.lua"), Long.class);
	private final IExchangeCodeService exchangeCodeService;
	// private final RedisLock redisLock;
	private final RedissonClient redissonClient;
	private final StringRedisTemplate redisTemplate;
	@Autowired
	@Lazy
	private ICouponService couponService;
	@Autowired
	private RabbitMqHelper rabbitMqHelper;

	public static void main(String[] args) {
		// Define the LocalDateTime
//		LocalDateTime localDateTime = LocalDateTime.of(2024, 11, 28, 10, 30, 50);
		LocalDateTime now = LocalDateTime.now();
		// Convert to Unix timestamp (seconds since epoch)
		// Specify the time zone
		ZoneId zoneId = ZoneId.of("Asia/Shanghai");

		// Convert to ZonedDateTime
		ZonedDateTime zonedDateTime = now.atZone(zoneId);
		long unixTimestamp = zonedDateTime.toEpochSecond();
		// Print the result
		System.out.println("Unix Timestamp: " + unixTimestamp);
	}

	/**
	 * （lua版本工具方法）转换时间LocalDateTime为long类型unixTimeStamp
	 */
	private long convertDateTimeToEpochSecond(LocalDateTime time) {
		ZonedDateTime zonedDateTime = time.atZone(PromotionLuaConstants.ZONE_ID);
		return zonedDateTime.toEpochSecond();
	}

	/**
	 * （lua版本工具方法）读库写优惠劵信息到Redis
	 */
	private void writeDbCouponInfoToRedis(Long couponId) {
		// * 查库
		Coupon coupon = couponService.getById(couponId);
		if (coupon == null) {
			throw new DbException("目标优惠劵不存在：" + couponId);
		}
		// * 不在发放状态不能抢
		if (coupon.getStatus() != CouponStatus.ISSUING) {
			throw new BizIllegalException("该优惠劵不在发放状态");
		}
		// * lua脚本原子性HSETNX写入Redis，乐观锁
		String key = PromotionConstants.COUPON_CACHE_PREFIX + couponId;
		// * 由于Redis中lua在沙箱环境中执行，没有os等模块，需要手动转换时间字符串
		// * 不如业务层转换为直接存为unix timestamp （单位为s，对应redis）
		String issueBeginTime = String.valueOf(convertDateTimeToEpochSecond(coupon.getIssueBeginTime()));
		String issueEndTime = String.valueOf(convertDateTimeToEpochSecond(coupon.getIssueEndTime()));
		Integer leftNum = coupon.getTotalNum() - coupon.getIssueNum();
		String totalNum = leftNum.toString();
		String userLimit = coupon.getUserLimit().toString();
		redisTemplate.execute(WRITE_COUPON_SCRIPT, List.of(key), issueBeginTime, issueEndTime, totalNum,
				userLimit);
	}

	/**
	 * （lua版本工具方法）读库写用户卷数量信息到Redis
	 */
	private void writeDbUserCouponCountToRedis(Long couponId, Long userId) {
		// * 不计状态，统计所有已领取
		Integer count = lambdaQuery()
				.eq(UserCoupon::getCouponId, couponId)
				.eq(UserCoupon::getUserId, userId)
				.count();
		// * 无数据数据库COUNT返回0
		if (count == null) {
			throw new DbException("用户卷数据库异常");
		}
		String key = PromotionConstants.USER_COUPON_CACHE_PREFIX + couponId;
		// * HSETNX，乐观锁
		redisTemplate.opsForHash().putIfAbsent(key, userId.toString(), count.toString());
	}

	/**
	 * （lua实现工具函数）根据返回值得到业务失败原因
	 */
	private void validateReceiveCouponBiz(Long result) {
		if (result == PromotionLuaConstants.INVALID_TIME) {
			throw new BizIllegalException("不在领取时间");
		}
		if (result == PromotionLuaConstants.INVALID_INVENTORY) {
			throw new BizIllegalException("库存不足");
		}
		if (result == PromotionLuaConstants.EXCEED_USER_LIMIT) {
			throw new BizIllegalException("用户领取数量已达上限");
		}
	}

	/**
	 * 领取优惠劵（分布式锁）乐观锁版本 lua实现 Redis为正确数据，无超卖，无刷卷
	 */
	@Override
	public void receiveCouponImplWithLua(Long couponId) {
		// * 获取lua脚本入参
		String couponKey = PromotionConstants.COUPON_CACHE_PREFIX + couponId;
		String userCouponKey = PromotionConstants.USER_COUPON_CACHE_PREFIX + couponId;
		Long userId = UserContext.getUser();
		// * 第一次尝试执行脚本
		Long result = redisTemplate.execute(RECEIVE_COUPON_SCRIPT, List.of(couponKey, userCouponKey), userId.toString());
		// * 健壮性判断
		if (result == null) {
			return;
		}
		// * 如果lua返回不成功
		if (result != PromotionLuaConstants.SUCCESS) {
			// * 校验是否因为业务原因失败
			validateReceiveCouponBiz(result);
			// * 非业务原因失败
			// * SETNX/HSETNX 乐观锁读库写入Redis
			if (result == PromotionLuaConstants.ONLY_COUPON_NOT_EXIST) {
				writeDbCouponInfoToRedis(couponId);
			}
			if (result == PromotionLuaConstants.ONLY_USER_COUPON_NOT_EXIST) {
				writeDbUserCouponCountToRedis(couponId, userId);
			}
			if (result == PromotionLuaConstants.BOTH_NOT_EXIST) {
				writeDbCouponInfoToRedis(couponId);
				writeDbUserCouponCountToRedis(couponId, userId);
			}
			// * 第二次尝试执行脚本（只在Redis操作数据，保障两边都是正确状态的数据）
			result = redisTemplate.execute(RECEIVE_COUPON_SCRIPT, List.of(couponKey, userCouponKey), userId.toString());
			// * 健壮性判断
			if (result == null) {
				return;
			}
			if (result != PromotionLuaConstants.SUCCESS) {
				validateReceiveCouponBiz(result);
				// * 如果仍是缺少数据，抛出异常
				throw new BizIllegalException("领取优惠劵失败");
			}
		}
		// * 成功，发送MQ
		UserCouponDTO dto = new UserCouponDTO();
		dto.setCouponId(couponId);
		dto.setUserId(userId);

		rabbitMqHelper.send(MqConstants.Exchange.PROMOTION_EXCHANGE, MqConstants.Key.COUPON_RECEIVED, dto);
	}

	/**
	 * 核销优惠劵
	 */
	@Override
	@Transactional
	public void writeOffCoupon(List<Long> userCouponIds) {
		// * 查询用户卷
		List<UserCoupon> userCoupons = listByIds(userCouponIds);
		if (CollUtils.isEmpty(userCoupons)) {
			throw new DbException("没有对应id的优惠卷可以核销");
		}
		// * 过滤无效卷
		List<UserCoupon> updateList = userCoupons.stream()
				.filter(c -> {
					if (c == null) {
						return false;
					}
					if (c.getStatus() != UserCouponStatus.UNUSED) {
						return false;
					}
					LocalDateTime now = LocalDateTime.now();
					return !now.isAfter(c.getTermEndTime()) && !now.isBefore(c.getTermBeginTime());
				})
				// * 构造更新用数据
				// 组织新增数据
				.map(coupon -> {
					UserCoupon c = new UserCoupon();
					c.setId(coupon.getId());
					c.setStatus(UserCouponStatus.USED);
					return c;
				})
				.collect(Collectors.toList());

		// * 修改优惠券状态
		boolean success = updateBatchById(updateList);
		if (!success) {
			throw new DbException("更新优惠劵状态核销失败");
		}
		// * 更新优惠劵表已使用数
		List<Long> couponIds = userCoupons.stream().map(UserCoupon::getCouponId).collect(Collectors.toList());
		success = couponService.lambdaUpdate()
				.setSql("used_num = used_num + 1")
				.in(Coupon::getId, couponIds)
				.update();
		if (!success) {
			throw new DbException("更新优惠劵表已使用数失败");
		}
	}

	@Override
	@Transactional
	public void refundCoupon(List<Long> userCouponIds) {
		// * 查询优惠劵
		List<UserCoupon> userCoupons = listByIds(userCouponIds);
		if (CollUtils.isEmpty(userCoupons)) {
			throw new DbException("数据库无对应id用户卷");
		}
		// * 构造更新
		List<UserCoupon> updateList = userCoupons.stream()
				.filter(coupon -> coupon != null && UserCouponStatus.USED == coupon.getStatus())
				.map(coupon -> {
					UserCoupon c = new UserCoupon();
					c.setId(coupon.getId());
					LocalDateTime now = LocalDateTime.now();
					UserCouponStatus status = now.isAfter(coupon.getTermEndTime()) ?
							UserCouponStatus.EXPIRED : UserCouponStatus.UNUSED;
					c.setStatus(status);
					return c;
				}).collect(Collectors.toList());

		boolean success = updateBatchById(updateList);
		if (!success) {
			throw new DbException("修改用户卷状态失败");
		}
		// * 更新优惠劵表已使用数
		List<Long> couponIds = userCoupons.stream().map(UserCoupon::getCouponId).collect(Collectors.toList());
		success = couponService.lambdaUpdate()
				.setSql("used_num = used_num - 1")
				.in(Coupon::getId, couponIds)
				.update();
		if (!success) {
			throw new DbException("更新优惠劵表已使用数失败");
		}
	}

	@Override
	public List<String> queryDiscountRules(List<Long> userCouponIds) {
		List<Coupon> coupons = getBaseMapper().queryCouponByUserCouponIds(userCouponIds, UserCouponStatus.USED);
		if (CollUtils.isEmpty(coupons)) {
			return CollUtils.emptyList();
		}
		return coupons.stream()
				.map(c -> DiscountStrategy.getDiscount(c.getDiscountType()).getRule(c))
				.collect(Collectors.toList());
	}

	private void cacheCouponInfo(Coupon coupon) {
		String key = PromotionConstants.COUPON_CACHE_PREFIX + coupon.getId();
		String issueBeginTime = coupon.getIssueBeginTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		String issueEndTime = coupon.getIssueEndTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		String totalNum = coupon.getTotalNum().toString();
		String userLimit = coupon.getUserLimit().toString();
		redisTemplate.execute(WRITE_COUPON_SCRIPT, List.of(key), issueBeginTime, issueEndTime, totalNum, userLimit);
	}

	/**
	 * 选择lua版本后的对外redis写入函数
	 */
	@Override
	public void cacheCouponInfoWithLua(Coupon coupon) {
		// * lua脚本原子性HSETNX写入Redis，乐观锁
		String key = PromotionConstants.COUPON_CACHE_PREFIX + coupon.getId();
		// * 由于Redis中lua在沙箱环境中执行，没有os等模块，需要手动转换时间字符串
		// * 不如业务层转换为直接存为unix timestamp （单位为s，对应redis）
		String issueBeginTime = String.valueOf(convertDateTimeToEpochSecond(coupon.getIssueBeginTime()));
		String issueEndTime = String.valueOf(convertDateTimeToEpochSecond(coupon.getIssueEndTime()));
		String totalNum = coupon.getTotalNum().toString();
		String userLimit = coupon.getUserLimit().toString();
		redisTemplate.execute(WRITE_COUPON_SCRIPT, List.of(key), issueBeginTime, issueEndTime, totalNum,
				userLimit);
	}

	/**
	 * 领取优惠劵（分布式锁）悲观锁版本（数据库正常，但Redis超卖并且消息队列可能堆积写，Redis数据不正确，不能直接用，比如直接判断用户领取的卷数量）
	 */
	@Override
	public void receiveCoupon(Long id) {
		// * 分布式锁防止个人刷单（或lua脚本保证操作原子性，无锁方案）
		String lockKey = PromotionConstants.COUPON_RECEIVE_REDIS_LOCK_PREFIX + UserContext.getUser();
		RLock lock = redissonClient.getLock(lockKey);
		boolean success = lock.tryLock();
		if (!success) {
			throw new BizIllegalException("领卷业务繁忙");
		}
		try {
			// * redis查询优惠劵信息
			// * 没有查询mysql并放入
			if (id == null) {
				return;
			}
			Coupon coupon = queryCouponByCache(id);
			boolean isFromRedis = coupon != null;
			// * redis无数据
			if (coupon == null) {
				// * 查数据库
				coupon = couponService.getById(id);
			}
			// * 校验优惠劵是否存在
			if (coupon == null) {
				throw new DbException("目标优惠劵不存在：" + id);
			}
			// * 如果不是从Redis获取，写入Redis重读
			if (!isFromRedis) {
				cacheCouponInfo(coupon);
				coupon = queryCouponByCache(id);
				if (coupon == null) {
					throw new BizIllegalException("优惠劵领取失败");
				}
			}
			// * 判断发放时间
			LocalDateTime now = LocalDateTime.now();
			if (now.isBefore(coupon.getIssueBeginTime()) || now.isAfter(coupon.getIssueEndTime())) {
				throw new BizIllegalException("优惠劵不在发放时间内");
			}
			// * 判断库存
			if (coupon.getTotalNum() <= 0) {
				throw new BizIllegalException("优惠劵库存不足");
			}
			// * 统计用户已领取数量
			String key = PromotionConstants.USER_COUPON_CACHE_PREFIX + id;
			Long userId = UserContext.getUser();
			// * 可以通过修改此处读取再校验更新为一句increment无锁
			Object result = redisTemplate.opsForHash().get(key, userId.toString());
			Integer receivedNum = 0;
			// * redis有数据
			if (result != null) {
				receivedNum = Integer.parseInt(result.toString());
			} else {
				// * 如无数据COUNT返回0
				receivedNum = lambdaQuery()
						.eq(UserCoupon::getUserId, userId)
						.eq(UserCoupon::getCouponId, id)
						.count();
			}
			// * 校验单个用户限制领取数
			if (receivedNum >= coupon.getUserLimit()) {
				throw new BizIllegalException("用户领取已达上限");
			}
			// * 更新Redis 用户已领取数量与totalNum
			redisTemplate.opsForHash().increment(key, userId.toString(), 1L + (result == null ? receivedNum : 0L));
			String couponCacheKey = PromotionConstants.COUPON_CACHE_PREFIX + id;
			// * 前面部分不加锁，可能出现超卖，需要校验结果
			Long totalNum = redisTemplate.opsForHash().increment(couponCacheKey, "totalNum", -1L);
			// * 推送消息至MQ
			if (totalNum >= 0) {
				UserCouponDTO dto = new UserCouponDTO();
				dto.setCouponId(id);
				dto.setUserId(userId);

				rabbitMqHelper.send(MqConstants.Exchange.PROMOTION_EXCHANGE, MqConstants.Key.COUPON_RECEIVED, dto);
			}
		} finally {
			lock.unlock();
		}
	}

	private Coupon queryCouponByCache(Long couponId) {
		String key = PromotionConstants.COUPON_CACHE_PREFIX + couponId;
		// * 取出原始map
		Map<Object, Object> couponInfoMap = redisTemplate.opsForHash().entries(key);
		if (CollUtils.isEmpty(couponInfoMap)) {
			return null;
		}
		// * map转对象
		return BeanUtils.mapToBean(couponInfoMap, Coupon.class, false, CopyOptions.create());
	}

	/**
	 * 校验单人领取数量并更新卷已领取数并添加用户卷记录（工具方法）
	 */
	@Transactional
	@Override
	public void checkAndCreateUserCoupon(Coupon coupon, Long userId) {
		// * 允许领取，优惠劵领取数+1
		boolean success = couponService.lambdaUpdate()
				.eq(Coupon::getId, coupon.getId())
				// * 乐观锁解决超卖
				.lt(Coupon::getIssueNum, coupon.getTotalNum())
				.setSql("issue_num = issue_num + 1")
				.update();
		if (!success) {
			throw new DbException("优惠卷领取更新失败");
		}
		// * 用户卷里加一条记录
		UserCoupon userCoupon = new UserCoupon();
		userCoupon.setUserId(userId);
		userCoupon.setCouponId(coupon.getId());
		// * 设置有效日期
		LocalDateTime termBeginTime = coupon.getTermBeginTime();
		LocalDateTime termEndTime = coupon.getTermEndTime();
		// * 使用天数而不是日期范围
		if (termBeginTime == null) {
			termBeginTime = LocalDateTime.now();
			termEndTime = termBeginTime.plusDays(coupon.getTermDays());
		}
		userCoupon.setTermBeginTime(termBeginTime);
		userCoupon.setTermEndTime(termEndTime);
		save(userCoupon);
	}

	/**
	 * 分页查询我的优惠劵
	 */
	@Override
	public PageDTO<CouponVO> queryMyCoupon(UserCouponQuery query) {
		// * 获取筛选状态
		Integer status = query.getStatus();
		// * 分页查询用户优惠劵
		Page<UserCoupon> page = lambdaQuery()
				.eq(UserCoupon::getUserId, UserContext.getUser())
				.eq(status != null, UserCoupon::getStatus, status)
				.page(query.toMpPageDefaultSortByCreateTimeDesc());
		List<UserCoupon> records = page.getRecords();
		// * 无用户卷数据
		if (CollUtils.isEmpty(records)) {
			return PageDTO.empty(0L, 0L);
		}
		// * 构造优惠劵id集合查询优惠劵信息
		List<Long> couponIds = records.stream()
				.map(UserCoupon::getCouponId)
				.collect(Collectors.toList());
		List<Coupon> couponList = couponService.lambdaQuery()
				.in(Coupon::getId, couponIds)
				.list();
		// * 无对应优惠劵
		if (CollUtil.isEmpty(couponList)) {
			throw new DbException("部分id优惠劵不存在");
		}
		// * 构造map
		Map<Long, LocalDateTime> termEndTimeMap = records.stream()
				.collect(Collectors.toMap(UserCoupon::getCouponId, UserCoupon::getTermEndTime));
		// * 补全vo的使用结束时间（与用户卷表有关）
		List<CouponVO> voList = new ArrayList<>();
		for (Coupon coupon : couponList) {
			CouponVO vo = BeanUtils.copyBean(coupon, CouponVO.class);
			vo.setTermEndTime(termEndTimeMap.getOrDefault(coupon.getId(), null));
			voList.add(vo);
		}
		return PageDTO.of(page, voList);
	}

	/**
	 * 兑换优惠劵（lua版本）
	 */
	@Override
	public void exchangeCouponWithLua(String code) {
		// * 不应用redis查询是否使用过，如果内存淘汰了bitmap，下次访问更大序列号时会给较小的位填0，并不能说明就没有使用过
		// * 并且一张卷也就只能用一次，正常调用接口对同一张卷查询并不频繁
		// * 由于相比领取卷，唯一额外的地方bitmap与zset只用于获取couponId与判使用与否
		// * 此处查库已经解决这两者，故而直接复用代码
		// * 解析兑换码
		long id = CodeUtil.parseCode(code);
		// * 查询兑换码
		ExchangeCode exchangeCode = exchangeCodeService.getById(id);
		// * 是否存在
		if (exchangeCode == null) {
			throw new DbException("目标兑换码不存在：" + id);
		}
		// * 判断是否兑换状态
		// * 判断是否过期
		LocalDateTime now = LocalDateTime.now();
		if (exchangeCode.getStatus() != ExchangeCodeStatus.UNUSED || now.isAfter(exchangeCode.getExpiredTime())) {
			throw new BizIllegalException("兑换码已使用或已过期");
		}
		Long couponId = exchangeCode.getExchangeTargetId();
		// * 获取lua脚本入参
		String couponKey = PromotionConstants.COUPON_CACHE_PREFIX + couponId;
		String userCouponKey = PromotionConstants.USER_COUPON_CACHE_PREFIX + couponId;
		String exchangeCouponKey = PromotionConstants.EXCHANGE_COUPON_CACHE_PREFIX + code;
		Long userId = UserContext.getUser();
		// * 第一次尝试执行脚本
		Long result = redisTemplate.execute(EXCHANGE_COUPON_SCRIPT, List.of(couponKey, userCouponKey, exchangeCouponKey),
				userId.toString());
		// * 健壮性判断
		if (result == null) {
			return;
		}
		// * 如果lua返回不成功
		if (result != PromotionLuaConstants.SUCCESS) {
			// * 校验是否因为业务原因失败
			validateReceiveCouponBiz(result);
			// * 非业务原因失败
			// * SETNX/HSETNX 乐观锁读库写入Redis
			if (result == PromotionLuaConstants.ONLY_COUPON_NOT_EXIST) {
				writeDbCouponInfoToRedis(couponId);
			}
			if (result == PromotionLuaConstants.ONLY_USER_COUPON_NOT_EXIST) {
				writeDbUserCouponCountToRedis(couponId, userId);
			}
			if (result == PromotionLuaConstants.BOTH_NOT_EXIST) {
				writeDbCouponInfoToRedis(couponId);
				writeDbUserCouponCountToRedis(couponId, userId);
			}
			// * 第二次尝试执行脚本（只在Redis操作数据，保障两边都是正确状态的数据）
			result = redisTemplate.execute(EXCHANGE_COUPON_SCRIPT, List.of(couponKey, userCouponKey, exchangeCouponKey),
					userId.toString());
			// * 健壮性判断
			if (result == null) {
				return;
			}
			if (result != PromotionLuaConstants.SUCCESS) {
				validateReceiveCouponBiz(result);
				// * 如果仍是缺少数据，抛出异常
				throw new BizIllegalException("领取优惠劵失败");
			}
		}
		// * 成功，发送MQ
		UserCouponDTO dto = new UserCouponDTO();
		dto.setCouponId(couponId);
		dto.setUserId(userId);
		dto.setSerialNum(exchangeCode.getId());
		rabbitMqHelper.send(MqConstants.Exchange.PROMOTION_EXCHANGE, MqConstants.Key.COUPON_EXCHANGED, dto);
	}

	/**
	 * 兑换优惠劵
	 */
	@Override
	public void exchangeCoupon(String code) {
		// * 解析兑换码
		long id = CodeUtil.parseCode(code);
		// * 查询兑换码
		ExchangeCode exchangeCode = exchangeCodeService.getById(id);
		// * 是否存在
		if (exchangeCode == null) {
			throw new DbException("目标兑换码不存在：" + id);
		}
		// * 判断是否兑换状态
		// * 判断是否过期
		LocalDateTime now = LocalDateTime.now();
		if (exchangeCode.getStatus() != ExchangeCodeStatus.UNUSED || now.isAfter(exchangeCode.getExpiredTime())) {
			throw new BizIllegalException("兑换码已使用或已过期");
		}
		// * 判断是否超出领取数量
		// * 更新状态（优惠卷领取+1；用户卷新增记录）
		Coupon coupon = couponService.getById(exchangeCode.getExchangeTargetId());
		Long userId = UserContext.getUser();
		// * 分布式锁
		String key = PromotionConstants.COUPON_EXCHANGE_REDIS_LOCK_PREFIX + userId;
		RLock lock = redissonClient.getLock(key);
		// * 30s lock 10s 检查续期 重新为30s
		boolean result = lock.tryLock();
		if (!result) {
			throw new BizIllegalException("兑换优惠劵业务操作过于频繁");
		}
		try {
			IUserCouponService userCouponService = (IUserCouponService) AopContext.currentProxy();
			userCouponService.checkAndCreateUserCouponWithCode(coupon, userId, exchangeCode.getId());
		} finally {
			lock.unlock();
		}
	}

	/**
	 * 校验单人领取数量并更新卷已领取数并添加用户卷记录并更新兑换码状态（工具方法）
	 */
	@Transactional
	@Override
	public void checkAndCreateUserCouponWithCode(Coupon coupon, Long userId, Long serialNum) {
		// * 代理对象确保声明式事务有效
		IUserCouponService userCouponService = (IUserCouponService) AopContext.currentProxy();
		userCouponService.checkAndCreateUserCoupon(coupon, userId);
		// * 更新兑换码状态
		exchangeCodeService.lambdaUpdate()
				.eq(ExchangeCode::getId, serialNum)
				.set(ExchangeCode::getUserId, userId)
				.set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
				.update();
	}
}
