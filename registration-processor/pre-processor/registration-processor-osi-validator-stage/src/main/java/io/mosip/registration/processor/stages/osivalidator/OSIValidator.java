package io.mosip.registration.processor.stages.osivalidator;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.IOUtils;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.kernel.core.fsadapter.spi.FileSystemAdapter;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.keygenerator.bouncycastle.KeyGenerator;
import io.mosip.registration.processor.core.auth.dto.AuthRequestDTO;
import io.mosip.registration.processor.core.auth.dto.AuthTypeDTO;
import io.mosip.registration.processor.core.auth.dto.BioInfo;
import io.mosip.registration.processor.core.auth.dto.DataInfoDTO;
import io.mosip.registration.processor.core.auth.dto.IdentityDTO;
import io.mosip.registration.processor.core.auth.dto.IdentityInfoDTO;
import io.mosip.registration.processor.core.auth.dto.PinInfo;
import io.mosip.registration.processor.core.code.RegistrationExceptionTypeCode;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;
import io.mosip.registration.processor.core.constant.PacketFiles;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.util.PacketStructure;
import io.mosip.registration.processor.core.exception.util.PlatformErrorMessages;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.packet.dto.FieldValueArray;
import io.mosip.registration.processor.core.packet.dto.Identity;
import io.mosip.registration.processor.core.packet.dto.PacketMetaInfo;
import io.mosip.registration.processor.core.packet.dto.RegOsiDto;
import io.mosip.registration.processor.core.packet.dto.demographicinfo.identify.RegistrationProcessorIdentity;
import io.mosip.registration.processor.core.spi.packetmanager.PacketInfoManager;
import io.mosip.registration.processor.core.spi.restclient.RegistrationProcessorRestClientService;
import io.mosip.registration.processor.core.util.IdentityIteratorUtil;
import io.mosip.registration.processor.core.util.JsonUtil;
import io.mosip.registration.processor.core.util.RegistrationExceptionMapperUtil;
import io.mosip.registration.processor.packet.storage.dto.ApplicantInfoDto;
import io.mosip.registration.processor.packet.storage.exception.IdentityNotFoundException;
import io.mosip.registration.processor.packet.storage.utils.ABISHandlerUtil;
import io.mosip.registration.processor.packet.storage.utils.Utilities;
import io.mosip.registration.processor.stages.osivalidator.utils.OSIUtils;
import io.mosip.registration.processor.stages.osivalidator.utils.StatusMessage;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.dto.SyncTypeDto;
import io.mosip.registration.processor.status.service.RegistrationStatusService;

/**
 * The Class OSIValidator.
 */
@Service
public class OSIValidator {

	/** The reg proc logger. */
	private static Logger regProcLogger = RegProcessorLogger.getLogger(OSIValidatorStage.class);

	/** The packet info manager. */
	@Autowired
	PacketInfoManager<Identity, ApplicantInfoDto> packetInfoManager;

	/** The Constant FILE_SEPARATOR. */
	public static final String FILE_SEPARATOR = "\\";

	/** The Constant BIOMETRIC_INTRODUCER. */
	public static final String BIOMETRIC = PacketFiles.BIOMETRIC.name() + FILE_SEPARATOR;
	/** The registration status service. */
	@Autowired
	RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

	/** The adapter. */
	@Autowired
	private FileSystemAdapter adapter;

	/** The rest client service. */
	@Autowired
	RegistrationProcessorRestClientService<Object> restClientService;

	/** The env. */
	@Autowired
	private Environment env;

	/** The osi utils. */
	@Autowired
	private OSIUtils osiUtils;

	@Autowired
	ABISHandlerUtil abisHandlerUtil;

	private JSONObject demographicIdentity;

	private RegistrationProcessorIdentity regProcessorIdentityJson;

	/** The message. */
	private String message = null;

	/** The Constant TRUE. */
	private static final String TRUE = "true";

	/** The registration status dto. */
	InternalRegistrationStatusDto registrationStatusDto;

	/** The auth request DTO. */
	AuthRequestDTO authRequestDTO = new AuthRequestDTO();

	/** The auth type DTO. */
	AuthTypeDTO authTypeDTO = new AuthTypeDTO();

	/** The identity DTO. */
	IdentityDTO identityDTO = new IdentityDTO();

	/** The identity info DTO. */
	IdentityInfoDTO identityInfoDTO = new IdentityInfoDTO();

	/** The pin info. */
	PinInfo pinInfo = new PinInfo();

	/** The identity iterator util. */
	IdentityIteratorUtil identityIteratorUtil = new IdentityIteratorUtil();

	@Value("${mosip.kernel.applicant.type.age.limit}")
	private String ageLimit;

	@Value("${registration.processor.applicant.dob.format}")
	private String dobFormat;

	@Autowired
	private Utilities utility;

	RegistrationExceptionMapperUtil registrationExceptionMapperUtil = new RegistrationExceptionMapperUtil();

	/** The key generator. */
	@Autowired
	private KeyGenerator keyGenerator;

	/**
	 * Checks if is valid OSI.
	 *
	 * @param registrationId
	 *            the registration id
	 * @return true, if is valid OSI
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 * @throws ApisResourceAccessException
	 *             the apis resource access exception
	 */
	public boolean isValidOSI(String registrationId) throws IOException, ApisResourceAccessException {
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
				registrationId, "OSIValidator::isValidOSI()::entry");
		boolean isValidOsi = false;
		demographicIdentity = getDemoIdentity(registrationId);
		regProcessorIdentityJson = getIdentity();
		Identity identity = osiUtils.getIdentity(registrationId);
		/** Getting data from packet MetadataInfo */
		RegOsiDto regOsi = osiUtils.getOSIDetailsFromMetaInfo(registrationId, identity);
		String officerId = regOsi.getOfficerId();
		String supervisorId = regOsi.getSupervisorId();
		if (officerId == null && supervisorId == null) {
			registrationStatusDto
					.setStatusComment(StatusMessage.OSI_VALIDATION_FAILURE + " Officer and Supervisor are null");
			return false;
		}
		if (((isValidOperator(regOsi, registrationId)) && (isValidSupervisor(regOsi, registrationId)))
				&& (isValidIntroducer(registrationId)))
			isValidOsi = true;
		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
				registrationId, "OSIValidator::isValidOSI()::exit");
		return isValidOsi;
	}

	/**
	 * Checks if is valid operator.
	 *
	 * @param regOsi
	 *            the reg osi
	 * @param registrationId
	 *            the registration id
	 * @return true, if is valid operator
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 * @throws ApisResourceAccessException
	 *             the apis resource access exception
	 */
	private boolean isValidOperator(RegOsiDto regOsi, String registrationId)
			throws IOException, ApisResourceAccessException {

		String officerId = regOsi.getOfficerId();
		if (officerId != null) {
			// officer password and otp check
			String officerPassword = regOsi.getOfficerHashedPwd();
			String officerOTPAuthentication = regOsi.getOfficerOTPAuthentication();

			String fingerPrint = null;// regOsi.getOfficerFingerpImageName();
			String fingerPrintType = null;// regOsi.getOfficerfingerType();
			String iris = null;// regOsi.getOfficerIrisImageName();
			String irisType = null;// regOsi.getOfficerIrisType();
			String face = null;// regOsi.getOfficerPhotoName();
			String pin = null;// regOsi.getOfficerHashedPin();

			if (checkBiometricNull(fingerPrint, iris, face, pin)) {
				boolean flag = validateOtpAndPwd(officerPassword, officerOTPAuthentication);
				if (flag) {
					registrationStatusDto
							.setStatusComment(StatusMessage.VALIDATION_DETAILS_SUCCESS + StatusMessage.OPERATOR);
				} else {
					registrationStatusDto
							.setStatusComment(StatusMessage.VALIDATION_DETAILS_FAILURE + StatusMessage.OPERATOR);

				}
				return flag;
			} else if ((validateUIN(officerId)) && validateOtpAndPwd(officerPassword, officerOTPAuthentication)) {
				return true;
			} else {
				registrationStatusDto.setStatusComment(StatusMessage.OPERATOR + message);
				return false;
			}
		}
		return true;
	}

	/**
	 * Check biometric null.
	 *
	 * @param fingerPrint
	 *            the finger print
	 * @param iris
	 *            the iris
	 * @param face
	 *            the face
	 * @param pin
	 *            the pin
	 * @return true, if successful
	 */
	boolean checkBiometricNull(String fingerPrint, String iris, String face, String pin) {
		return (fingerPrint == null) && (iris == null) && (face == null) && (pin == null);
	}

	/**
	 * Checks if is valid supervisor.
	 *
	 * @param regOsi
	 *            the reg osi
	 * @param registrationId
	 *            the registration id
	 * @return true, if is valid supervisor
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 * @throws ApisResourceAccessException
	 *             the apis resource access exception
	 */
	private boolean isValidSupervisor(RegOsiDto regOsi, String registrationId)
			throws IOException, ApisResourceAccessException {
		String supervisorId = regOsi.getSupervisorId();
		if (supervisorId != null) {
			// superVisior otp and password
			String supervisiorPassword = regOsi.getSupervisorHashedPwd();
			String supervisorOTPAuthentication = regOsi.getSupervisorOTPAuthentication();

			String fingerPrint = null;// regOsi.getSupervisorBiometricFileName();
			String fingerPrintType = null;// regOsi.getSupervisorFingerType();
			String iris = null;// regOsi.getSupervisorIrisImageName();
			String irisType = null;// regOsi.getSupervisorIrisType();
			String face = null;// regOsi.getSupervisorPhotoName();
			String pin = null;// regOsi.getSupervisorHashedPin();

			if (checkBiometricNull(fingerPrint, iris, face, pin)) {
				boolean flag = validateOtpAndPwd(supervisiorPassword, supervisorOTPAuthentication);
				if (flag) {
					registrationStatusDto
							.setStatusComment(StatusMessage.VALIDATION_DETAILS_SUCCESS + StatusMessage.SUPERVISOR);
				} else {
					registrationStatusDto
							.setStatusComment(StatusMessage.VALIDATION_DETAILS_FAILURE + StatusMessage.SUPERVISOR);
				}
				return flag;
			} else if ((validateUIN(supervisorId))
					&& validateOtpAndPwd(supervisiorPassword, supervisorOTPAuthentication)) {
				return true;
			} else {
				registrationStatusDto.setStatusComment(StatusMessage.SUPERVISOR + message);
				return false;
			}
		}
		return true;
	}

	/**
	 * Checks if is valid introducer.
	 *
	 * @param regOsi
	 *            the reg osi
	 * @param registrationId
	 *            the registration id
	 * @return true, if is valid introducer
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 * @throws ApisResourceAccessException
	 *             the apis resource access exception
	 */
	private boolean isValidIntroducer(String registrationId) throws IOException, ApisResourceAccessException {

		if (registrationStatusDto.getRegistrationType().equalsIgnoreCase(SyncTypeDto.NEW.name())
				|| (registrationStatusDto.getRegistrationType().equalsIgnoreCase(SyncTypeDto.UPDATE.name()))) {
			int age = utility.getApplicantAge(registrationId);
			int ageThreshold = Integer.parseInt(ageLimit);
			if (age < ageThreshold) {
				String introducerUinLabel = regProcessorIdentityJson.getIdentity().getParentOrGuardianUIN().getValue();
				String introducerRidLabel = regProcessorIdentityJson.getIdentity().getParentOrGuardianRID().getValue();
				Number introducerUinNumber = JsonUtil.getJSONValue(demographicIdentity, introducerUinLabel);
				Number introducerRidNumber = JsonUtil.getJSONValue(demographicIdentity, introducerRidLabel);
				String introducerBiometricsLabel = regProcessorIdentityJson.getIdentity()
						.getParentOrGuardianBiometrics().getValue();
				String introducerBiometricsFileName = JsonUtil.getJSONValue(demographicIdentity,
						introducerBiometricsLabel);
				BigInteger introducerUIN = numberToBigInteger(introducerUinNumber);
				BigInteger introducerRID = numberToBigInteger(introducerRidNumber);
				if (introducerUIN == null && introducerRID == null) {
					registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
							.getStatusCode(RegistrationExceptionTypeCode.PARENT_UIN_AND_RID_NOT_IN_PACKET));
					registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.toString());
					registrationStatusDto
							.setStatusComment(StatusMessage.PARENT_UIN_AND_RID_NOT_IN_PACKET + registrationId);
					return false;
				}
				String introducerRidString = bigIntegerToString(introducerRID);
				String introducerUinString = bigIntegerToString(introducerUIN);
				if (introducerUinString == null && validateIntroducerRid(introducerRidString)) {

					introducerUinString = abisHandlerUtil.getUinFromIDRepo(introducerRidString).toString();
					if (introducerUinString == null) {
						registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
								.getStatusCode(RegistrationExceptionTypeCode.PARENT_UIN_NOT_AVAIALBLE));
						registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.toString());
						registrationStatusDto.setStatusComment(StatusMessage.PARENT_UIN_NOT_AVAIALBLE + registrationId);
						return false;
					}

				}
				if (introducerUinString != null) {
					return validateIntroducer(registrationId, introducerUinString, introducerBiometricsFileName);
				}
			}

		}

		return true;
	}

	private BigInteger numberToBigInteger(Number number) {
		return number != null ? new BigInteger(String.valueOf(number)) : null;
	}

	private String bigIntegerToString(BigInteger number) {

		return number != null ? String.valueOf(number) : null;
	}

	private int getApplicantAge(String registrationId) throws IOException {
		String ageKey = regProcessorIdentityJson.getIdentity().getAge().getValue();
		String dobKey = regProcessorIdentityJson.getIdentity().getDob().getValue();

		String applicantDob = JsonUtil.getJSONValue(demographicIdentity, dobKey);
		try {
			if (applicantDob != null) {
				DateFormat sdf = new SimpleDateFormat(dobFormat);
				Date birthDate = sdf.parse(applicantDob);
				LocalDate ld = new java.sql.Date(birthDate.getTime()).toLocalDate();
				Period p = Period.between(ld, LocalDate.now());
				return p.getYears();
			} else {
				return JsonUtil.getJSONValue(demographicIdentity, ageKey);
			}
		} catch (ParseException e) {
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationId, e.getMessage());
		}
		return 0;
	}

	private RegistrationProcessorIdentity getIdentity() throws JsonParseException, JsonMappingException, IOException {
		String getIdentityJsonString = Utilities.getJson(utility.getConfigServerFileStorageURL(),
				utility.getGetRegProcessorIdentityJson());
		ObjectMapper mapIdentityJsonStringToObject = new ObjectMapper();
		RegistrationProcessorIdentity regProcessorIdentityJson = mapIdentityJsonStringToObject
				.readValue(getIdentityJsonString, RegistrationProcessorIdentity.class);
		return regProcessorIdentityJson;
	}

	private JSONObject getDemoIdentity(String registrationId) throws IOException {
		InputStream documentInfoStream = adapter.getFile(registrationId,
				PacketFiles.DEMOGRAPHIC.name() + FILE_SEPARATOR + PacketFiles.ID.name());

		byte[] bytes = IOUtils.toByteArray(documentInfoStream);
		String demographicJsonString = new String(bytes);
		JSONObject demographicJson = (JSONObject) JsonUtil.objectMapperReadValue(demographicJsonString,
				JSONObject.class);
		JSONObject demographicIdentity = JsonUtil.getJSONObject(demographicJson,
				utility.getGetRegProcessorDemographicIdentity());
		if (demographicIdentity == null)
			throw new IdentityNotFoundException(PlatformErrorMessages.RPR_PIS_IDENTITY_NOT_FOUND.getMessage());
		return demographicIdentity;
	}

	/**
	 * Sets the finger biometric dto.
	 *
	 * @param obj
	 *            the obj
	 * @param fieldName
	 *            the field name
	 * @param value
	 *            the value
	 */
	private void setFingerBiometricDto(IdentityDTO obj, String fieldName, Object value) {
		PropertyDescriptor pd;
		try {
			pd = new PropertyDescriptor(fieldName, obj.getClass());
			pd.getWriteMethod().invoke(obj, value);
		} catch (IntrospectionException | IllegalAccessException | IllegalArgumentException
				| InvocationTargetException e) {
			regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					"OSIValidator", e.getMessage());
		}
	}

	/**
	 * Sets the finger biometric.
	 *
	 * @param biometricData
	 *            the biometric data
	 * @param type
	 *            the type
	 */
	void setFingerBiometric(List<IdentityInfoDTO> biometricData, String type) {
		String finger = null;
		String[] fingerType = env.getProperty("registration.processor.fingerType").split(",");
		List<String> list = new ArrayList<>(Arrays.asList(fingerType));
		finger = type;
		Iterator<String> it = list.iterator();
		while (it.hasNext()) {
			String ftype = it.next();
			if (ftype.equalsIgnoreCase(type)) {
				finger = ftype;
				break;
			}
		}
		this.setFingerBiometricDto(identityDTO, finger, biometricData);
	}

	/**
	 * Validate UIN.
	 *
	 * @param uin
	 *            the uin
	 * @return true, if successful
	 */
	private boolean validateUIN(String uin) {
		// todo To call IAM rest API for UNI validation
		return true;
	}

	/**
	 * Validate otp and pwd.
	 *
	 * @param password
	 *            the password
	 * @param otp
	 *            the otp
	 * @return true, if successful
	 */
	boolean validateOtpAndPwd(String password, String otp) {
		if (password != null && password.equals(TRUE) || otp != null && otp.equals(TRUE)) {
			return true;
		} else {
			message = StatusMessage.VALIDATE_OTP_PASSWORD;
			return false;

		}

	}

	/**
	 * Validate introducer.
	 *
	 * @param regOsi
	 *            the reg osi
	 * @param registrationId
	 *            the registration id
	 * @param introducerUin
	 *            the introducer uin
	 * @return true, if successful
	 * @throws ApisResourceAccessException
	 *             the apis resource access exception
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */

	private boolean validateIntroducer(String registrationId, String introducerUin, String introducerBiometricsFile)
			throws ApisResourceAccessException, IOException {
		if (introducerBiometricsFile != null && (!introducerBiometricsFile.trim().isEmpty())) {
			InputStream packetMetaInfoStream = adapter.getFile(registrationId,
					PacketStructure.BIOMETRIC + introducerBiometricsFile.toUpperCase());
		} else {
			registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
					.getStatusCode(RegistrationExceptionTypeCode.PARENT_BIOMETRIC_NOT_IN_PACKET));
			registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.toString());
			registrationStatusDto.setStatusComment(StatusMessage.PARENT_BIOMETRIC_NOT_IN_PACKET + registrationId);
			return false;
		}
		return true;

	}

	/**
	 * Validate introducer rid.
	 *
	 * @param introducerRid
	 *            the introducer rid
	 * @param registrationId
	 *            the registration id
	 * @return true, if successful
	 */
	private boolean validateIntroducerRid(String introducerRid) {
		InternalRegistrationStatusDto introducerRegistrationStatusDto = registrationStatusService
				.getRegistrationStatus(introducerRid);
		if (introducerRegistrationStatusDto != null) {
			if (registrationStatusDto.getStatusCode().equals(RegistrationStatusCode.PROCESSING.toString())) {
				registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
						.getStatusCode(RegistrationExceptionTypeCode.OSI_FAILED_ON_HOLD_PARENT_PACKET));

				registrationStatusDto.setStatusComment(StatusMessage.PACKET_IS_ON_HOLD);
				registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());
				return false;
			} else if (registrationStatusDto.getStatusCode().equals(RegistrationStatusCode.REJECTED.toString())
					|| registrationStatusDto.getStatusCode().equals(RegistrationStatusCode.FAILED.toString())) {
				registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
						.getStatusCode(RegistrationExceptionTypeCode.OSI_FAILED_REJECTED_PARENT));

				registrationStatusDto.setStatusComment(StatusMessage.OSI_FAILED_REJECTED_PARENT);
				registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.toString());
				return false;
			} else {
				return true;
			}

		} else {
			registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
					.getStatusCode(RegistrationExceptionTypeCode.OSI_FAILED_ON_HOLD_PARENT_PACKET));

			registrationStatusDto.setStatusComment(StatusMessage.PACKET_IS_ON_HOLD);
			registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());
			return false;
		}

	}

	/**
	 * Gets the hash sequence value.
	 *
	 * @param registrationId
	 *            the registration id
	 * @param field
	 *            the field
	 * @return the hash sequence value
	 * @throws UnsupportedEncodingException
	 *             the unsupported encoding exception
	 */
	private String getHashSequenceValue(String registrationId, String field) throws UnsupportedEncodingException {

		Identity identity = getIdentity(registrationId);
		List<FieldValueArray> hashSequence = identity.getHashSequence1();
		List<String> hashList = identityIteratorUtil.getHashSequence(hashSequence, field);
		if (hashList != null)
			return hashList.get(0).toUpperCase();

		return null;

	}

	/**
	 * Gets the identity.
	 *
	 * @param registrationId
	 *            the registration id
	 * @return the identity
	 * @throws UnsupportedEncodingException
	 *             the unsupported encoding exception
	 */
	private Identity getIdentity(String registrationId) throws UnsupportedEncodingException {
		InputStream packetMetaInfoStream = adapter.getFile(registrationId, PacketFiles.PACKET_META_INFO.name());
		PacketMetaInfo packetMetaInfo = (PacketMetaInfo) JsonUtil.inputStreamtoJavaObject(packetMetaInfoStream,
				PacketMetaInfo.class);
		return packetMetaInfo.getIdentity();

	}


	private List<BioInfo> getBioInfoListDto (byte[] cbefByteFile) throws ParserConfigurationException, SAXException, IOException {

		List<BioInfo> biometrics = null;
		BioInfo bioInfo=new BioInfo();
		DataInfoDTO dataInfoDTO=new DataInfoDTO();
		String byteFileStr = new String(cbefByteFile);
		InputSource is = new InputSource();
		is.setCharacterStream(new StringReader(byteFileStr));

		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		dbFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
		Document doc=dBuilder.parse(is);
		doc.getDocumentElement().normalize();
		if (doc != null) {
			NodeList birList = doc.getElementsByTagName("BIR");
			for (int bl = 0; bl < birList.getLength(); bl++) {
				NodeList bdbInfo = doc.getElementsByTagName("BDBInfo");
				for (int bi = 0; bi < bdbInfo.getLength(); bi++) {
					Node bdbInfoList = bdbInfo.item(bi);
					if (bdbInfoList.getNodeType() == Node.ELEMENT_NODE) {

						Element eElement = (Element) bdbInfoList;
						dataInfoDTO.setBioType(eElement.getElementsByTagName("Type").item(0).getTextContent());

						dataInfoDTO.setBioSubType(eElement.getElementsByTagName("Subtype").item(0).getTextContent());
						dataInfoDTO.setBioValue(eElement.getElementsByTagName("firstname").item(0).getTextContent());

					}
					NodeList bdb = doc.getElementsByTagName("BDB");
					String value = bdb.item(0).getTextContent();
					dataInfoDTO.setBioValue(value);
					bioInfo.setData(dataInfoDTO);

					biometrics.add(bioInfo);

				}
			}
		}
		return biometrics;
	}
}
