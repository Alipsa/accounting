<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>${title}</h2>
  <p class="lead">${lead}</p>
  <ul class="summary">
    <#list summaryLines as line>
      <li>${line}</li>
    </#list>
  </ul>
  <table class="report-table dense-report-table transaction-report-table">
    <colgroup>
      <col class="date-col">
      <col class="voucher-col">
      <col class="account-col">
      <col class="account-name-col">
      <col class="voucher-text-col">
      <col class="line-text-col">
      <col class="amount-col">
      <col class="amount-col">
      <col class="status-col">
    </colgroup>
    <thead>
      <tr>
        <th class="date">${tableHeaders[0]}</th>
        <th>${tableHeaders[1]}</th>
        <th class="code">${tableHeaders[2]}</th>
        <th>${tableHeaders[3]}</th>
        <th>${tableHeaders[4]}</th>
        <th>${tableHeaders[5]}</th>
        <th class="number">${tableHeaders[6]}</th>
        <th class="number">${tableHeaders[7]}</th>
        <th>${tableHeaders[8]}</th>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <tr>
          <td class="date">${row[0]}</td>
          <td>${row[1]}</td>
          <td class="code">${row[2]}</td>
          <td>${row[3]}</td>
          <td>${row[4]}</td>
          <td>${row[5]}</td>
          <td class="number">${row[6]}</td>
          <td class="number">${row[7]}</td>
          <td>${row[8]}</td>
        </tr>
      </#list>
    </tbody>
  </table>
</@layout.page>
