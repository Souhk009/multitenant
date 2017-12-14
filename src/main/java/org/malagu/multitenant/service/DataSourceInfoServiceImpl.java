package org.malagu.multitenant.service;

import java.util.List;

import org.malagu.linq.JpaUtil;
import org.malagu.multitenant.domain.DataSourceInfo;
import org.malagu.multitenant.domain.Organization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;


/**
 * @author Kevin Yang (mailto:muxiangqiu@gmail.com)
 * @since 2017年11月24日
 */
@Service
@Transactional(readOnly = true)
public class DataSourceInfoServiceImpl implements DataSourceInfoService {

	@Override
	public DataSourceInfo get(Organization organization) {
		return JpaUtil.linq(DataSourceInfo.class)
			.addIf(organization.getDataSourceInfoId())
				.idEqual(organization.getDataSourceInfoId())
			.endIf()
			.addIfNot(organization.getDataSourceInfoId())
				.exists(Organization.class)
					.equalProperty("dataSourceInfoId", "id")
					.idEqual(organization.getId())
				.end()
			.endIf()
			.findOne();
	}

	@Override
	public DataSourceInfo allocate(Organization organization) {
		if (StringUtils.isEmpty(organization.getDataSourceInfoId())) {
			List<DataSourceInfo> list = JpaUtil.linq(DataSourceInfo.class)
					.isTrue("enabled")
					.isTrue("shared")
					.addIfNot(organization.getDataSourceInfoId())
						.notExists(DataSourceInfo.class)
							.isTrue("enabled")
							.isTrue("shared")
							.lt("depletionIndex", "depletionIndex")
						.end()
					.endIf()
					.list(0, 1);
			Assert.notEmpty(list,"DataSourceInfo must contain elements");
			return list.get(0);
		} else {
			return JpaUtil.linq(DataSourceInfo.class)
					.isTrue("enabled")
					.isTrue("shared")
					.idEqual(organization.getDataSourceInfoId())
					.findOne();
		}
		
	}

}
