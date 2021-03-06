/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.vffacility;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.ObjectType;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilFormatOut;
import org.apache.ofbiz.base.util.UtilGenerics;
import org.apache.ofbiz.base.util.UtilHttp;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilNumber;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericPK;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityConditionList;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.entity.util.EntityUtil;
import org.apache.ofbiz.order.shoppingcart.ShoppingCart;
import org.apache.ofbiz.order.shoppingcart.ShoppingCartEvents;
import org.apache.ofbiz.order.shoppingcart.product.ProductPromoWorker;
import org.apache.ofbiz.product.catalog.CatalogWorker;
import org.apache.ofbiz.product.config.ProductConfigWorker;
import org.apache.ofbiz.product.config.ProductConfigWrapper;
import org.apache.ofbiz.product.product.ProductWorker;
import org.apache.ofbiz.product.store.ProductStoreSurveyWrapper;
import org.apache.ofbiz.product.store.ProductStoreWorker;
import org.apache.ofbiz.security.Security;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ModelService;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.webapp.control.RequestHandler;

/**
 * Vforder events.
 */
public class VffacilityEvents {

	public static String module = VffacilityEvents.class.getName();

	public static final String resource = "VffacilityUiLabels";
	public static final String resource_error = "OrderErrorUiLabels";

	private static final String NO_ERROR = "noerror";
	private static final String NON_CRITICAL_ERROR = "noncritical";
	private static final String ERROR = "error";

	public static final MathContext generalRounding = new MathContext(10);

	public static String updateInventoryShipment(HttpServletRequest request, HttpServletResponse response) {

		Delegator delegator = (Delegator) request.getAttribute("delegator");
		LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
		GenericValue userLogin = (GenericValue) request.getSession().getAttribute("userLogin");
		String shipmentId = null;
		String facilityId = null;
		String isReturnShipment = null;

		// Get the parameters as a MAP, remove the productId and quantity
		// params.
		Map<String, Object> paramMap = UtilHttp.getParameterMap(request);

		if (paramMap.containsKey("shipmentId")) {
			shipmentId = (String) paramMap.remove("shipmentId");
		}
		if (paramMap.containsKey("facilityId")) {
			facilityId = (String) paramMap.remove("facilityId");
		}
		// if isReturnShipment == Y we restore the items in the facility.
		if (paramMap.containsKey("isReturnShipment")) {
			isReturnShipment = (String) paramMap.remove("isReturnShipment");
		}

		GenericValue shipment = null;
		try {
			shipment = delegator.findOne("Shipment", UtilMisc.toMap("shipmentId", shipmentId), false);

			if ("Y".equals(isReturnShipment)) {
				List<GenericValue> invDetails = delegator.findByAnd("InventoryItemDetail", UtilMisc.toMap("shipmentId", shipmentId), null, false);
				if (invDetails != null) {
					List<EntityCondition> exprs = new LinkedList<EntityCondition>();
					for (GenericValue det : invDetails) {
						exprs.add(EntityCondition.makeCondition("inventoryItemId", EntityOperator.EQUALS, det.get("inventoryItemId")));
					}
					delegator.removeByAnd("InventoryItemDetail", UtilMisc.toMap("shipmentId", shipmentId));
					EntityConditionList<EntityCondition> ecl = EntityCondition.makeCondition(exprs, EntityOperator.OR);
					List<GenericValue> inv = EntityQuery.use(delegator).from("InventoryItem").where(ecl).queryList();
					delegator.removeAll(inv);
				}

				shipment.put("statusId", "SHIPMENT_CANCELLED");
			} else {
				List<GenericValue> shipmentItems = null;
				try {
					shipmentItems = delegator.findByAnd("ShippingItemView", UtilMisc.toMap("shipmentId", shipmentId), null, false);

				} catch (GenericEntityException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				if (shipmentItems != null) {
					for (GenericValue si : shipmentItems) {

						try {
							BigDecimal qty = (BigDecimal) si.get("quantity");
							Map serviceCtx = UtilMisc.toMap("productId", si.get("productId"), "facilityId", facilityId, "shipmentId", shipmentId, "userLogin",
									userLogin, "quantityOnHandDiff", qty.negate());

							dispatcher.runSync("vfcreateInventoryItemDetailItem", serviceCtx);

						} catch (GenericServiceException e) {
							Debug.logError(e, module);
							return "error";
						}

					}

				}
				shipment.put("statusId", "SHIPMENT_SHIPPED");

			}
			delegator.store(shipment);
		} catch (GenericEntityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return "success";

	}

	public static String updateProductionInventory(HttpServletRequest request, HttpServletResponse response) {

		Delegator delegator = (Delegator) request.getAttribute("delegator");
		LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
		GenericValue userLogin = (GenericValue) request.getSession().getAttribute("userLogin");
		BigDecimal quantity = BigDecimal.ZERO;
		String facilityId = null;
		String productId = null;
		Map<String, Object> paramMap = UtilHttp.getParameterMap(request);

		if (paramMap.containsKey("facilityId")) {
			facilityId = (String) paramMap.remove("facilityId");
		}

		if (paramMap.containsKey("productId")) {
			productId = (String) paramMap.remove("productId");
		}
		String quantityStr = null;
		if (paramMap.containsKey("quantity")) {
			quantityStr = (String) paramMap.remove("quantity");
		}
		if ((quantityStr == null) || (quantityStr.equals(""))) {
			quantityStr = "0";
		}
		try {
			quantity = new BigDecimal(quantityStr);
		} catch (Exception e) {
			Debug.logWarning(e, "Problems parsing quantity string: " + quantityStr, module);
			quantity = BigDecimal.ZERO;
		}

		// product And assosiations
		List<GenericValue> productAssoc = null;
		try {
			productAssoc = delegator.findByAnd("VfProductAndAssoc", UtilMisc.toMap("paProductId", productId), null, false);

		} catch (GenericEntityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		String workEffortName = "Production: " + productId;
		String workEffortId = null;

		// create workeffort for production
		try {
			Map serviceCtx = UtilMisc.toMap("workEffortTypeId", "PROD_ORDER_TASK", "workEffortName", workEffortName, "currentStatusId", "PRUN_COMPLETED",
					"userLogin", userLogin);
			Map<String, Object> res = dispatcher.runSync("createWorkEffort", serviceCtx);
			workEffortId = (String) res.get("workEffortId");

		} catch (GenericServiceException e) {
			Debug.logError(e, module);
		}

		if (productAssoc != null) {
			for (GenericValue pa : productAssoc) {
				// update raw material inventory
				try {
					BigDecimal qty = quantity.multiply((BigDecimal) pa.get("paQuantity"));
					Map serviceCtx = UtilMisc.toMap("productId", pa.get("paProductIdTo"), "facilityId", facilityId, "workEffortId", workEffortId, "userLogin",
							userLogin, "quantityOnHandDiff", qty.negate());

					dispatcher.runSync("vfcreateInventoryItemDetailItem", serviceCtx);

				} catch (GenericServiceException e) {
					Debug.logError(e, module);
					return "error";
				}
			}
			// update finish product inventory
			try {
				Map serviceCtx = UtilMisc.toMap("productId", productId, "facilityId", facilityId, "workEffortId", workEffortId, "userLogin", userLogin,
						"quantityOnHandDiff", quantity);

				Map invItem = dispatcher.runSync("vfcreateInventoryItemDetailItem", serviceCtx);

				// <!-- create WorkEffortInventoryAssign record -->
				Map serviceCtx2 = UtilMisc.toMap("inventoryItemId", invItem.get("inventoryItemId"), "workEffortId", workEffortId, "userLogin", userLogin,
						"quantity", quantity);

				dispatcher.runSync("assignInventoryToWorkEffort", serviceCtx2);

			} catch (GenericServiceException e) {
				Debug.logError(e, module);
				return "error";
			}

		}

		return "success";

	}

	public static String deleteProductionInventory(HttpServletRequest request, HttpServletResponse response) {

		Delegator delegator = (Delegator) request.getAttribute("delegator");
		LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
		GenericValue userLogin = (GenericValue) request.getSession().getAttribute("userLogin");
		String workEffortId = null;
		Map<String, Object> paramMap = UtilHttp.getParameterMap(request);

		if (paramMap.containsKey("workEffortId")) {
			workEffortId = (String) paramMap.remove("workEffortId");
		}

		try {

			List<GenericValue> invItemDetails = delegator.findByAnd("InventoryItemDetail", UtilMisc.toMap("workEffortId", workEffortId), null, false);
			List<EntityCondition> exprs = new LinkedList<EntityCondition>();
			for (GenericValue det : invItemDetails) {
				exprs.add(EntityCondition.makeCondition("inventoryItemId", EntityOperator.EQUALS, det.get("inventoryItemId")));
			}
			EntityConditionList<EntityCondition> ecl = EntityCondition.makeCondition(exprs, EntityOperator.OR);
			List<GenericValue> inv = EntityQuery.use(delegator).from("InventoryItem").where(ecl).queryList();

			delegator.removeByAnd("WorkEffortInventoryAssign", UtilMisc.toMap("workEffortId", workEffortId));
			delegator.removeByAnd("InventoryItemDetail", UtilMisc.toMap("workEffortId", workEffortId));
			delegator.removeAll(inv);
			delegator.removeByAnd("WorkEffortKeyword", UtilMisc.toMap("workEffortId", workEffortId));
			delegator.removeByAnd("WorkEffortStatus", UtilMisc.toMap("workEffortId", workEffortId));
			delegator.removeByAnd("WorkEffort", UtilMisc.toMap("workEffortId", workEffortId));

		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return "error";
		}

		return "success";

	}

}
