package com.kryptoeuro.accountmapper.rest;

import com.codeborne.security.mobileid.MobileIDSession;
import com.kryptoeuro.accountmapper.command.AuthenticateCommand;
import com.kryptoeuro.accountmapper.command.PollCommand;
import com.kryptoeuro.accountmapper.domain.EthereumAccount;
import com.kryptoeuro.accountmapper.domain.PendingAuthorisation;
import com.kryptoeuro.accountmapper.response.AccountsResponse;
import com.kryptoeuro.accountmapper.response.AuthenticateResponse;
import com.kryptoeuro.accountmapper.response.PollResponse;
import com.kryptoeuro.accountmapper.service.AccountManagementService;
import com.kryptoeuro.accountmapper.service.EthereumService;
import com.kryptoeuro.accountmapper.service.MobileIdAuthService;
import com.kryptoeuro.accountmapper.service.PendingAuthorisationService;
import com.kryptoeuro.accountmapper.state.AuthenticationStatus;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

import static org.springframework.web.bind.annotation.RequestMethod.*;

@RestController
@RequestMapping("/v1")
@CrossOrigin(origins = "*")
@Slf4j
public class AccountMapperController {
	@Autowired
	MobileIdAuthService mobileIdAuthService;
	@Autowired
	EthereumService ethereumService;
	@Autowired
	AccountManagementService accountManagementService;
	@Autowired
	PendingAuthorisationService pendingAuthorisationService;

	private static boolean accountActivationEnabled = true;

	@ApiOperation(value = "Initiate mobile-id authorisation")
	@RequestMapping(
			method = POST,
			value = "/authorisations",
			consumes = MediaType.APPLICATION_JSON_UTF8_VALUE)
	public ResponseEntity<AuthenticateResponse> authenticate(@Valid @RequestBody AuthenticateCommand authenticateCommand) {
		// start mobile id auth
		MobileIDSession mobileIDSession = mobileIdAuthService.startLogin(authenticateCommand.getPhoneNumber());

		PendingAuthorisation pendingAuthorisation = pendingAuthorisationService.store(authenticateCommand.getAccountAddress(), mobileIDSession);

		AuthenticateResponse authenticateResponse = new AuthenticateResponse(mobileIDSession.challenge, pendingAuthorisation.getAuthIdentifier());
		return new ResponseEntity<AuthenticateResponse>(authenticateResponse, HttpStatus.OK);
	}

	@ApiOperation(value = "[Polling endpoint] Validate authorisation, store new account-identity mapping and activate ethereum account")
	@RequestMapping(
			method = POST,
			value = "/accounts",
			consumes = MediaType.APPLICATION_JSON_UTF8_VALUE)
	public ResponseEntity<PollResponse> authorizeAndCreateAccountIdentityMapping(@Valid @RequestBody PollCommand pollCommand) {
		PendingAuthorisation pendingAuthorisation = pendingAuthorisationService.findByAuthIdentifier(pollCommand.getAuthIdentifier());

		if (pendingAuthorisation == null) {
			return new ResponseEntity<PollResponse>(new PollResponse(AuthenticationStatus.LOGIN_EXPIRED), HttpStatus.OK);
		}

		MobileIDSession mobileIDSession = MobileIDSession.fromString(pendingAuthorisation.getSerialisedMobileIdSession());
		String accountAddress = pendingAuthorisation.getAddress();

		if (mobileIDSession == null || accountAddress == null) {
			return new ResponseEntity<PollResponse>(new PollResponse(AuthenticationStatus.LOGIN_EXPIRED), HttpStatus.OK);
		}

		// Check if authenticated
		if (mobileIdAuthService.isLoginComplete(mobileIDSession)) {
			pendingAuthorisationService.expire(pendingAuthorisation);
		} else {
			return new ResponseEntity<PollResponse>(new PollResponse(AuthenticationStatus.LOGIN_PENDING), HttpStatus.OK);
		}

		EthereumAccount newAccount;
		try {
			newAccount = accountManagementService.storeNewAccount(accountAddress, mobileIDSession.personalCode);

			if (accountActivationEnabled) {
				ethereumService.activateEthereumAccount(accountAddress);
			}
		} catch (Exception e) {
            log.error("Login failure", e);
			return new ResponseEntity<PollResponse>(new PollResponse(AuthenticationStatus.LOGIN_FAILURE), HttpStatus.OK);
		}

		accountManagementService.markActivated(newAccount);

		return new ResponseEntity<PollResponse>(new PollResponse(AuthenticationStatus.LOGIN_SUCCESS), HttpStatus.OK);
	}

	@ApiOperation(value = "View existing accounts")
	@RequestMapping(method = GET, value = "/accounts")
	public ResponseEntity<AccountsResponse> listAccounts(@RequestParam(name = "ownerId", required = false) String ownerId) {
		if (ownerId != null) {
			return new ResponseEntity<AccountsResponse>(AccountsResponse.fromEthereumAccounts(accountManagementService.getAccountsByOwnerId(ownerId)), HttpStatus.OK);
		}
		return new ResponseEntity<AccountsResponse>(AccountsResponse.fromEthereumAccounts(accountManagementService.getAllAccounts()), HttpStatus.OK);
	}

	@ApiOperation(value = "Remove account identity mapping")
	@RequestMapping(method = DELETE, value = "/accounts")
	public ResponseEntity<AccountsResponse> removeAccount(@RequestParam(name = "mappingId", required = true) Long mappingId) {
		accountManagementService.removeAccountById(mappingId);
		return new ResponseEntity<AccountsResponse>(AccountsResponse.fromEthereumAccounts(accountManagementService.getAllAccounts()), HttpStatus.OK);
	}
}
