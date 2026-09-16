local totals = {}
for i, key in ipairs(KEYS) do
    local requested = tonumber(ARGV[i])
    if not requested or requested <= 0 or requested ~= math.floor(requested) then
        return redis.error_reply('Invalid stock quantity')
    end
    totals[key] = (totals[key] or 0) + requested
end
for key, requested in pairs(totals) do
    local value = redis.call('GET', key)
    if not value then return {2, key} end
    local current = tonumber(value)
    if not current then return redis.error_reply('Invalid cached stock') end
    if current < requested then
        return {0, string.sub(key, 7)}
    end
end
for key, requested in pairs(totals) do
    redis.call('DECRBY', key, requested)
end
return {1, ''}
