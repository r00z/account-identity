package com.kryptoeuro.accountmapper.command;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

@Data
@NoArgsConstructor
public class AuthenticateCommand {
	@NotNull
	String accountAddress;
	@NotNull
	String phoneNumber;
}
