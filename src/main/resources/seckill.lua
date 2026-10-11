-- 参数：库存、用户、订单 id
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

-- key：库存和订单
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

-- 判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 扣减库存
redis.call('incrby', stockKey, -1)

-- 下单
redis.call('sadd', orderKey, userId)

-- 发送消息到 MQ
redis.call('xadd', "stream.voucher.order", "*", "userId", userId, "voucherId", voucherId, "id", orderId)
return 0