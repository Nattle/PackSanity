local packsanity_epoch_seconds = __PACKSANITY_EPOCH_SECONDS__
local function days_from_civil(year, month, day)
if month <= 2 then year = year - 1 end
local era = math.floor(year / 400)
local year_of_era = year - era * 400
local adjusted_month = month + (month > 2 and -3 or 9)
local day_of_year = math.floor((153 * adjusted_month + 2) / 5) + day - 1
local day_of_era = year_of_era * 365 + math.floor(year_of_era / 4) -
    math.floor(year_of_era / 100) + day_of_year
return era * 146097 + day_of_era - 719468
end
local function civil_from_days(days)
local shifted = days + 719468
local era = math.floor(shifted / 146097)
local day_of_era = shifted - era * 146097
local year_of_era = math.floor((day_of_era - math.floor(day_of_era / 1460) +
    math.floor(day_of_era / 36524) - math.floor(day_of_era / 146096)) / 365)
local year = year_of_era + era * 400
local day_of_year = day_of_era - (365 * year_of_era + math.floor(year_of_era / 4) -
    math.floor(year_of_era / 100))
local month_prime = math.floor((5 * day_of_year + 2) / 153)
local day = day_of_year - math.floor((153 * month_prime + 2) / 5) + 1
local month = month_prime + (month_prime < 10 and 3 or -9)
if month <= 2 then year = year + 1 end
return year, month, day
end
local function packsanity_os_time(value)
if value == nil then return packsanity_epoch_seconds end
if type(value) ~= 'table' then error("bad argument #1 to 'time' (table expected)") end
local days = days_from_civil(value.year, value.month, value.day)
return days * 86400 + (value.hour or 12) * 3600 + (value.min or 0) * 60 + (value.sec or 0)
end
local function packsanity_os_date(format, timestamp)
format = format or '%c'
timestamp = math.floor(timestamp or packsanity_epoch_seconds)
if format ~= '*t' and format ~= '!*t' then return '' end
local days = math.floor(timestamp / 86400)
local seconds = timestamp - days * 86400
local year, month, day = civil_from_days(days)
return {
    year = year, month = month, day = day,
    hour = math.floor(seconds / 3600),
    min = math.floor((seconds % 3600) / 60), sec = seconds % 60,
    wday = ((days + 4) % 7) + 1, yday = days - days_from_civil(year, 1, 1) + 1,
    isdst = false,
}
end
os = {
clock = function() return 0 end, difftime = function(a, b) return a - b end,
time = packsanity_os_time, date = packsanity_os_date,
}
-- Some packs explicitly require these standard modules. Keep require() pointed
-- at the sandboxed host tables instead of exposing Lua's filesystem libraries.
package.preload['io'] = function() return io end
package.preload['os'] = function() return os end
AutoTracker = {
ReadU8 = function(_) return 0 end, ReadU16 = function(_) return 0 end,
ReadU24 = function(_) return 0 end, ReadU32 = function(_) return 0 end,
ReadVariable = function(_) return 0 end,
AddMemoryWatch = function(_, _, _) return false end,
RemoveMemoryWatch = function(_) return false end,
GetConnectionState = function(self_or_backend, backend)
    backend = backend or self_or_backend
    if backend == 'AP' then return Archipelago.__connected and 3 or 0 end
    return 0
end,
}

