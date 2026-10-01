#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
为「离线模式（online-mode=false）」的 Paper 服务端生成 ops.json 条目。

离线模式的 UUID 规则是固定的（Java 版）：
    uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + 玩家名).encode("utf-8"))
即把一个 MD5 摘要按 RFC 4122 v3 处理：版本位置为 3，变体位为 2。

用法:
    python gen-offline-uuid.py <玩家名> [更多玩家名...]

输出:
    1) 每个玩家的离线 UUID
    2) 可直接粘贴进 server/ops.json 的 JSON 片段

注意: 这是给「你自己开的服」用的。正版模式（online-mode=true）下玩家
UUID 由 Mojang 账号决定，不能这样算 —— 让玩家进一次服再用
/autoop add <名字> 由插件记录即可。
"""

import hashlib
import json
import sys
import uuid


def offline_uuid(player_name: str) -> uuid.UUID:
    """等价于 Java 的 UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))."""
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + player_name).encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30  # version 3
    digest[8] = (digest[8] & 0x3F) | 0x80  # IETF variant
    return uuid.UUID(bytes=bytes(digest))


def main(argv):
    names = [a for a in argv[1:] if a.strip()]
    if not names:
        print(__doc__)
        return 2

    entries = []
    print("玩家名 -> 离线模式 UUID")
    print("-" * 56)
    for name in names:
        uid = offline_uuid(name)
        print(f"{name:<20} {uid}")
        entries.append({
            "uuid": str(uid),
            "name": name,
            "level": 4,
            "bypassesPlayerLimit": False,
        })

    print()
    print("粘贴进 ops.json 的条目（注意与已有条目合并成数组）:")
    print(json.dumps(entries, indent=2, ensure_ascii=False))
    print()
    print("提示: 更推荐直接用插件命令 —— 进服后执行 /autoop add <玩家名>，")
    print("      插件会写入 config.yml 的 players.uuid，无需手工改 ops.json。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
