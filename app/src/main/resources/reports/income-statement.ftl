<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <section class="income-statement-report">
    <h2 class="report-heading">${title}</h2>
    <#assign hasComparison = comparisonFiscalYear??>
    <table class="statement-table<#if !hasComparison> no-comparison</#if>">
      <colgroup>
        <col class="label-col">
        <col class="amount-col">
        <col class="percent-col">
        <col class="amount-col">
        <col class="percent-col">
        <#if hasComparison>
          <col class="prior-amount-col">
          <col class="comparison-col">
        </#if>
      </colgroup>
      <thead>
        <tr class="group-row">
          <th class="label">${tableHeaders[0]}</th>
          <th class="group-heading group-start" colspan="2">${tableHeaders[1]}</th>
          <th class="group-heading group-start" colspan="2">${tableHeaders[3]}</th>
          <#if hasComparison>
            <th class="group-heading group-start" colspan="2">${tableHeaders[5]}<#if comparisonFiscalYear.name?has_content> ${comparisonFiscalYear.name}</#if></th>
          </#if>
        </tr>
        <tr class="subheader-row">
          <th class="label"></th>
          <th class="number group-start">${amountColumnLabel}</th>
          <th class="number percent">${tableHeaders[2]}</th>
          <th class="number group-start">${amountColumnLabel}</th>
          <th class="number percent">${tableHeaders[4]}</th>
          <#if hasComparison>
            <th class="number group-start">${amountColumnLabel}</th>
            <th class="number percent">${tableHeaders[6]}</th>
          </#if>
        </tr>
      </thead>
      <tbody>
        <#list tableRows as row>
          <#assign typedRow = typedRows[row?index]>
          <#assign rowType = typedRow.rowType.name()?lower_case?replace("_", "-")>
          <tr class="statement-row ${rowType}">
            <td class="label">${row[0]}</td>
            <td class="number group-start">${row[1]!}</td>
            <td class="number percent">${row[2]!}</td>
            <td class="number group-start">${row[3]!}</td>
            <td class="number percent">${row[4]!}</td>
            <#if hasComparison>
              <td class="number group-start">${row[5]!}</td>
              <td class="number percent">${row[6]!}</td>
            </#if>
          </tr>
        </#list>
      </tbody>
    </table>
  </section>
</@layout.page>
