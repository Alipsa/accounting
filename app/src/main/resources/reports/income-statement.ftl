<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <section class="income-statement-report">
    <h2 class="report-heading">${title}</h2>
    <table class="statement-table">
      <colgroup>
        <col class="label-col">
        <col class="amount-col">
        <col class="percent-col">
        <col class="amount-col">
        <col class="percent-col">
        <col class="prior-amount-col">
        <col class="comparison-col">
      </colgroup>
      <thead>
        <tr class="group-row">
          <th class="label">${tableHeaders[0]}</th>
          <th class="group-heading" colspan="2">${tableHeaders[1]}</th>
          <th class="group-heading" colspan="2">${tableHeaders[3]}</th>
          <th class="group-heading" colspan="2">${tableHeaders[5]}</th>
        </tr>
        <tr class="subheader-row">
          <th class="label"></th>
          <th class="number">${amountColumnLabel}</th>
          <th class="number percent">${tableHeaders[2]}</th>
          <th class="number">${amountColumnLabel}</th>
          <th class="number percent">${tableHeaders[4]}</th>
          <th class="number">${amountColumnLabel}</th>
          <th class="number percent">${tableHeaders[6]}</th>
        </tr>
      </thead>
      <tbody>
        <#list tableRows as row>
          <#assign typedRow = typedRows[row?index]>
          <#assign rowType = typedRow.rowType.name()?lower_case?replace("_", "-")>
          <tr class="statement-row ${rowType}">
            <td class="label">${row[0]}</td>
            <td class="number">${row[1]!}</td>
            <td class="number percent">${row[2]!}</td>
            <td class="number">${row[3]!}</td>
            <td class="number percent">${row[4]!}</td>
            <td class="number">${row[5]!}</td>
            <td class="number percent">${row[6]!}</td>
          </tr>
        </#list>
      </tbody>
    </table>
  </section>
</@layout.page>
