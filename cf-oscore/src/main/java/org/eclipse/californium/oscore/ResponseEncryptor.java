/*******************************************************************************
 * Copyright (c) 2019 RISE SICS and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Joakim Brorsson
 *    Ludwig Seitz (RISE SICS)
 *    Tobias Andersson (RISE SICS)
 *    Rikard Höglund (RISE SICS)
 *    
 ******************************************************************************/
package org.eclipse.californium.oscore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upokecenter.cbor.CBORObject;

import java.util.Objects;

import org.apache.hc.client5.http.utils.Hex;
import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.option.BlockOption;
import org.eclipse.californium.cose.Encrypt0Message;
import org.eclipse.californium.elements.util.Bytes;
import org.eclipse.californium.oscore.group.OptionEncoder;

/**
 * 
 * Encrypts an OSCORE Response.
 *
 */
public class ResponseEncryptor extends Encryptor {

	/**
	 * The logger
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ResponseEncryptor.class);

	/**
	 * @param db the context DB
	 * @param response the response
	 * @param ctx the OSCore context
	 * @param newPartialIV boolean to indicate whether to use a new partial IV
	 *            or not
	 * @param outerBlockwise boolean to indicate whether the block-wise options
	 *            should be encrypted or not
	 * @param requestSequenceNr sequence number (Partial IV) from the request
	 *            (if encrypting a response)
	 * 
	 * @return the response with the encrypted OSCore option
	 * 
	 * @throws OSException when encryption fails
	 */
	public static Response encrypt(OSCoreCtxDB db, Response response, OSCoreCtx ctx, boolean newPartialIV,
			boolean outerBlockwise, int requestSequenceNr, CBORObject[] instructions) throws OSException {

		byte[] oldOscoreOption = response.getOptions().getOscore(); // can be null
		boolean instructionsExists = Objects.nonNull(instructions);
		
		if (ctx == null) {
			LOGGER.error(ErrorDescriptions.CTX_NULL);
			throw new OSException(ErrorDescriptions.CTX_NULL);
		}

		// Perform context re-derivation procedure if ongoing
		try {
			ctx = ContextRederivation.outgoingResponse(db, ctx);
			newPartialIV |= ctx.getResponsesIncludePartialIV();

			// Ensure that the first response in the procedure is a 4.01
			if (ctx.getContextRederivationPhase() == ContextRederivation.PHASE.SERVER_PHASE_2) {
				response = OptionJuggle.setRealCodeResponse(response, ResponseCode.UNAUTHORIZED);
				response.setPayload(Bytes.EMPTY);
			}
		} catch (OSException e) {
			LOGGER.error(ErrorDescriptions.CONTEXT_REGENERATION_FAILED);
			throw new OSException(ErrorDescriptions.CONTEXT_REGENERATION_FAILED);
		}

		int realCode = response.getCode().value;
		response = OptionJuggle.setFakeCodeResponse(response);

		OptionSet options = response.getOptions();

		// Save block1 option in the case of outer block-wise to re-add later
		BlockOption block1Option = null;
		if (outerBlockwise) {
			block1Option = options.getBlock1();
			options.removeBlock1();
		}

		/*
		// what do when src endpoint is aware we are a reverse proxy?
		boolean instructionsExists = Objects.nonNull(instructions);
		if (instructionsExists && (int) instructions[1].ToObject(int.class) != 2) {
			System.out.println("adding from instructions");
			System.out.println(Hex.encodeHexString(oldOscoreOption));
			System.out.println(Hex.encodeHexString(options.getOscore()));
			System.out.println(Hex.encodeHexString(instructions[0].ToObject(byte[].class)));

			options.setOscore(instructions[0].ToObject(byte[].class));
		}
		else if (db != null && db.getIfProxyable() && oldOscoreOption != null) {
			System.out.println(Hex.encodeHexString(oldOscoreOption));
			System.out.println(Hex.encodeHexString(options.getOscore()));
			System.out.println("adding from is proxy old option");
			options.setOscore(oldOscoreOption);
		}
		else {
			if (oldOscoreOption != null) {
				System.out.println(Hex.encodeHexString(oldOscoreOption));
			}
			if (options.getOscore() != null) {
				System.out.println(Hex.encodeHexString(options.getOscore()));
			}
			System.out.println("removing");
			options.removeOscore();
		}*/

		OptionSet[] optionsUAndE = OptionJuggle.filterOptions(options);
		System.out.println("U OPTIONS ARE: " + optionsUAndE[0]);
		System.out.println("E OPTIONS ARE: " + optionsUAndE[1]);

		//if (instructionsExists ) {
			OptionSet promotedOptions = OptionJuggle.promotion(optionsUAndE[0], instructions, false);
			optionsUAndE[1] = OptionJuggle.merge(optionsUAndE[1], promotedOptions);	
		//}
		
		System.out.println("options to be encrypted: " + optionsUAndE[1]);
		System.out.println("raw payload size is: " + response.getPayload().length);
		System.out.println("code is size: 1");
		byte[] confidential = OSSerializer.serializeConfidentialData(optionsUAndE[1], response.getPayload(), realCode);
		System.out.println("Confidential data is size: " + confidential.length);
		
		Encrypt0Message enc = prepareCOSEStructure(confidential);
		byte[] cipherText = encryptAndEncode(enc, ctx, response, newPartialIV, requestSequenceNr);

		System.out.println("ciphertext should be " + (confidential.length + (ctx.getAlg().getTagSize() / Byte.SIZE)));
		System.out.println("ciphertext is size: " + cipherText.length);
		compression(ctx, cipherText, response, newPartialIV);


		byte[] oscoreOption = response.getOptions().getOscore();

		// here the U options are prepared
		response.setOptions(optionsUAndE[0]);
		response.getOptions().setOscore(oscoreOption);
		/*
		options = response.getOptions();
		response.setOptions(OptionJuggle.prepareUoptions(options));
		 */
		if (outerBlockwise) {
			response.setOptions(response.getOptions().setBlock1(block1Option));
		}

		//If new partial IV was generated for response increment sender seq nr.
		if (newPartialIV) {
			ctx.increaseSenderSeq();
		}

		return response;
	}
}
