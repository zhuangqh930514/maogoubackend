package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("user_account")
public class UserAccount {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String username;
    public String displayName;
    public String email;
    public String phone;
    public String passwordHash;
    public String status;
    public String systemRole;
    public String riskPreference;
    public LocalDateTime lastLoginAt;
    @TableLogic
    public Integer deleted;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
